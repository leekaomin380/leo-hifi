package com.leoaudio.hifi.singlevolume;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Candidate C: the volume keys move the ESS DAC while Android's media gain is
 * pinned at 0 dB.  Ordering is the safety property:
 *
 *   engage:  DAC to the equivalent level -> HAL guard armed -> media gain to max
 *   release: media gain back to the saved index -> guard disarmed
 *
 * While the guard is armed the HAL refuses the standard headphone path, so any
 * exit from HiFi that this class did not order is silent rather than loud.
 * Main-thread confined (controller callbacks and key events arrive there).
 */
public final class LeoHardVolume implements LeoHifiController.Callback {
    private static final String TAG = "LeoHardVolume";
    private static LeoHardVolume sInstance;
    private static final int STEP = 2;              // 2 dB per key press
    private static final long PHASE_TIMEOUT_MS = 6000, RETRY_AFTER_MS = 30000;
    /* Samples already mixed at 0 dB sit in the PCM/DSP buffers (~100-200 ms);
     * an ordered exit waits for them to play out before the route changes. */
    private static final long DRAIN_MS = 700, REENGAGE_AFTER_MS = 2000;
    private final Handler main = new Handler(Looper.getMainLooper());

    private enum Phase { IDLE, WAIT_LEVEL, WAIT_GUARD, PINNED }

    private final Context context;
    private final AudioManager audio;
    private final SharedPreferences prefs;
    private final LeoHifiController controller;
    private Phase phase;
    private int targetLevel;
    private long phaseSince, blockedUntil;
    private LeoHifiState last = LeoHifiState.unavailable("initializing", 0);
    static volatile boolean keyServiceConnected;

    public static synchronized LeoHardVolume get(Context context) {
        if (sInstance == null) sInstance = new LeoHardVolume(context.getApplicationContext());
        return sInstance;
    }

    private LeoHardVolume(Context context) {
        this.context = context;
        audio = context.getSystemService(AudioManager.class);
        prefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("leo_hardvol", Context.MODE_PRIVATE);
        phase = prefs.getBoolean("pinned", false) ? Phase.PINNED : Phase.IDLE;
        controller = LeoHifiController.get(context);
        controller.addCallback(this);
    }

    private static long now() { return SystemClock.elapsedRealtime(); }

    public boolean pinned() { return phase == Phase.PINNED; }

    public int level() { return last.schema5 ? last.volumeUser : prefs.getInt("level", 35); }

    /** Enabled in Settings, not merely bound: binding lags behind boot and updates. */
    boolean keyServiceEnabled() {
        String list = android.provider.Settings.Secure.getString(context.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return list != null && list.contains(context.getPackageName() + "/");
    }

    private boolean headphonesConnected() {
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if (d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET) return true;
        return false;
    }

    private int headphoneType() {
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if (d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET) return d.getType();
        return AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
    }

    @Override public void onStateChanged(LeoHifiState s) {
        last = s;
        if (!s.available || !s.isFresh(now())) return; // stale: keep whatever is safe now
        if (s.pending) return; // a mode/level transaction is in flight (e.g. boot restore)
        // A boot-time "not requested" is only the HAL default before the app
        // restores the saved mode; the armed guard keeps that window silent.
        boolean notRequested = !s.requested && !controller.modeRestorePending();
        boolean leaving = !s.supported || notRequested || !s.schema5
                || "wired_standard".equals(s.effective) || "error_fallback".equals(s.effective)
                || "hifi_degraded".equals(s.effective) || !keyServiceEnabled();
        switch (phase) {
            case PINNED:
                if (leaving || !s.hardvol) { release(leaving ? "left_hifi" : "guard_lost"); return; }
                // Android sometimes restores a lower headphone index after a reboot;
                // while pinned the media gain must stay 0 dB (the DAC holds the level).
                if (s.active) {
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != max) {
                        Log.i(TAG, "re-pin media gain to 0 dB");
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0);
                    }
                }
                return;
            case WAIT_LEVEL:
                if (leaving || now() - phaseSince > PHASE_TIMEOUT_MS) { abort("level_timeout"); return; }
                if (s.volumeUser == targetLevel && s.active
                        && s.volumeLeft == LeoHifiState.levelToCtl(targetLevel)) {
                    enter(Phase.WAIT_GUARD);
                    controller.requestArm();
                }
                return;
            case WAIT_GUARD:
                if (leaving || now() - phaseSince > PHASE_TIMEOUT_MS) { abort("guard_timeout"); return; }
                if (s.hardvol) pin();
                return;
            case IDLE:
                if (!leaving && s.active && now() >= blockedUntil
                        && headphonesConnected() && audio.getMode() == AudioManager.MODE_NORMAL)
                    engage();
                return;
        }
    }

    /** Same loudness as before: DAC level = -25 dB + current Android media gain. */
    private void engage() {
        int index = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        float gainDb = audio.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, headphoneType());
        if (Float.isNaN(gainDb) || Float.isInfinite(gainDb)) gainDb = -60f;
        int level = Math.round(60f - 25f + gainDb);
        level = Math.max(0, Math.min(LeoHifiState.LEVEL_MAX, level));
        prefs.edit().putInt("saved_index", index).putInt("level", level).apply();
        targetLevel = level;
        enter(Phase.WAIT_LEVEL);
        Log.i(TAG, "engage: index=" + index + " gain=" + gainDb + "dB -> level " + level);
        controller.requestLevel(level);
    }

    private void pin() {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0);
        prefs.edit().putBoolean("pinned", true).apply();
        enter(Phase.PINNED);
        Log.i(TAG, "pinned: media gain 0 dB, DAC level " + targetLevel);
    }

    /** Ordered exit (the user switches HiFi off): restore Android volume, wait for
     * the buffered 0 dB samples to drain, then disarm and run {@code next}. */
    public void releaseThen(String why, Runnable next) {
        boolean wasPinned = phase == Phase.PINNED || prefs.getBoolean("pinned", false);
        if (wasPinned) {
            int saved = prefs.getInt("saved_index", audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0);
        }
        prefs.edit().putBoolean("pinned", false).apply();
        if (phase != Phase.IDLE) Log.i(TAG, "release: " + why + (wasPinned ? " (draining)" : ""));
        enter(Phase.IDLE);
        blockedUntil = now() + DRAIN_MS + REENGAGE_AFTER_MS;
        main.postDelayed(() -> { controller.disarmNow(); next.run(); }, wasPinned ? DRAIN_MS : 0);
    }

    /** Unordered exit: the HAL guard has already made the standard path silent,
     * so restore Android volume first, then disarm. Safe in any phase. */
    public void release(String why) {
        if (phase == Phase.PINNED || prefs.getBoolean("pinned", false)) {
            int saved = prefs.getInt("saved_index", audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0);
        }
        controller.disarmNow();
        prefs.edit().putBoolean("pinned", false).apply();
        if (phase != Phase.IDLE) Log.i(TAG, "release: " + why);
        enter(Phase.IDLE);
        blockedUntil = now() + REENGAGE_AFTER_MS;
    }

    private void abort(String why) {
        Log.w(TAG, "engage aborted: " + why);
        controller.disarmNow(); // media gain was never raised in these phases
        enter(Phase.IDLE);
        blockedUntil = now() + RETRY_AFTER_MS;
    }

    private void enter(Phase p) { phase = p; phaseSince = now(); }

    /** Volume key while pinned: returns the new level, or -1 when not engaged. */
    public int onVolumeKey(boolean up) {
        if (phase != Phase.PINNED || !last.available || !last.supported || !last.requested
                || audio.getMode() != AudioManager.MODE_NORMAL || !headphonesConnected()) return -1;
        int base = prefs.getInt("level", level());
        int next = Math.max(0, Math.min(LeoHifiState.LEVEL_MAX, base + (up ? STEP : -STEP)));
        prefs.edit().putInt("level", next).apply();
        controller.requestLevel(next);
        return next;
    }
}
