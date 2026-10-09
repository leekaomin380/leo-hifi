package com.leoaudio.hifi.singlevolume;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;
import java.util.ArrayList;

/** Public-API ordinary-app controller. HAL is the only mixer writer.
 * setParameters has no return status; every acknowledgment requires a fresh,
 * identity-bound readback. No hidden API or privileged install is used.
 */
public final class LeoHifiController {
    private static final String TAG = "LeoHifi";
    private static LeoHifiController sInstance;
    private static final String SAVED = "confirmed_enable";

    /** True once a write has gone out without a real AudioFlinger status code behind it. */
    public static volatile boolean WRITE_STATUS_UNAVAILABLE = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler worker;
    private final Context context;
    private final AudioManager audio;
    private final SharedPreferences prefs;
    private final ArrayList<Callback> callbacks = new ArrayList<>();
    private volatile LeoHifiState state = LeoHifiState.unavailable("initializing", now());
    // Main-thread confined. A hung Binder occupies ONE worker. A timeout does
    // not cancel a HAL write already in progress: its result must be reconciled.
    private boolean busy, expired, restoreAttempted;
    // 0.4: nobody can see the badge while the screen is off, so the one-second
    // poll pauses. The badge then expires through MAX_AGE_MS as before.
    private boolean interactive = true;
    private long session, operation;
    private LeoHifiRequestGate deferredUserRequest; // At most one intent, bound to its original identity/deadline.
    private final Runnable poll = () -> begin(null);
    private final Runnable expiry = () -> {
        if (!state.isFresh(now())) publish(LeoHifiState.unavailable("stale", now()));
    };

    public interface Callback { void onStateChanged(LeoHifiState state); }

    public static synchronized LeoHifiController get(Context context) {
        if (sInstance == null) sInstance = new LeoHifiController(context.getApplicationContext());
        return sInstance;
    }

    private LeoHifiController(Context context) {
        this.context = context;
        this.audio = context.getSystemService(AudioManager.class);
        prefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("leo_hifi", Context.MODE_PRIVATE);
        HandlerThread thread = new HandlerThread("LeoHifi");
        thread.start(); worker = new Handler(thread.getLooper());
        if ("leo".equals(Build.DEVICE) && ownerProcess()) main.post(poll);
    }

    private static long now() { return SystemClock.elapsedRealtime(); }

    public LeoHifiState getState() { return state; }

    public void addCallback(Callback cb) {
        main.post(() -> { if (!callbacks.contains(cb)) callbacks.add(cb); cb.onStateChanged(state); });
    }

    public void removeCallback(Callback cb) { main.post(() -> callbacks.remove(cb)); }

    public void refresh() { main.post(() -> begin(null)); }

    /** Screen on resumes polling with an immediate read; screen off stops rescheduling. */
    public void setInteractive(boolean on) {
        main.post(() -> {
            if (interactive == on) return;
            interactive = on;
            if (on) begin(null);
            else main.removeCallbacks(poll);
        });
    }

    public void requestEnabled(boolean enabled) {
        // Leaving HiFi: restore Android volume and disarm the guard first, so the
        // standard path can never start at the pinned 0 dB gain.
        if (!enabled) {
            LeoHardVolume.get(context).releaseThen("hifi_off",
                    () -> request(LeoHifiRequestGate.MODE, 0));
            return;
        }
        request(LeoHifiRequestGate.MODE, 1);
    }

    /** After boot the HAL starts with HiFi off until the saved mode is restored. */
    public boolean modeRestorePending() {
        return !restoreAttempted && prefs.getBoolean(SAVED, false);
    }

    // ---- 0.6: DAC level and hard-volume guard ---------------------------------
    private int desiredLevel = -1;
    private boolean desiredArm;

    /** Latest wish wins; applied after a fresh read, also while the screen is off. */
    public void requestLevel(int level) {
        main.post(() -> { desiredLevel = level; pump(); });
    }

    public void requestArm() {
        main.post(() -> { desiredArm = true; pump(); });
    }

    /** Disarming bypasses the gate: it can only make the standard path audible. */
    public void disarmNow() {
        worker.post(() -> {
            try { halWrite(LeoHifiRequestGate.disarmParameter()); }
            catch (RuntimeException | LinkageError e) { Log.w(TAG, "disarm failed", e); }
        });
        refresh();
    }

    /** Starts at most one operation; returns true when one was started. */
    private boolean pump() {
        if (busy || (desiredLevel < 0 && !desiredArm)) return false;
        if (!state.isFresh(now())) { begin(null); return true; }
        if (desiredLevel >= 0) {
            LeoHifiState s = state;
            boolean done = s.volumeUser == desiredLevel && (!s.active
                    || s.volumeLeft == LeoHifiState.levelToCtl(desiredLevel));
            if (done || !LeoHifiRequestGate.canStart(s, now(), true, false,
                    LeoHifiRequestGate.VOLUME, desiredLevel)) {
                if (!done) publish(s.withReason("level_rejected"));
                desiredLevel = -1;
            } else {
                begin(new LeoHifiRequestGate(LeoHifiRequestGate.VOLUME, desiredLevel, s, now()));
                return true;
            }
        }
        if (desiredArm) {
            LeoHifiState s = state;
            desiredArm = false;
            if (!s.hardvol && LeoHifiRequestGate.canStart(s, now(), true, false,
                    LeoHifiRequestGate.HARDVOL, 1)) {
                begin(new LeoHifiRequestGate(LeoHifiRequestGate.HARDVOL, 1, s, now()));
                return true;
            }
        }
        return false;
    }


    /**
     * Delta 4: the original compared Process.myUserHandle() against UserHandle.SYSTEM, which
     * is @hide. userId is uid / PER_USER_RANGE and PER_USER_RANGE is 100000 on every Android
     * release, so dividing the public Process.myUid() yields the same answer with public API
     * only: this process belongs to the system user (userId 0).
     */
    private static boolean ownerProcess() {
        return android.os.Process.myUid() / 100000 == 0;
    }

    private boolean permitted() {
        try {
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            UserManager users = context.getSystemService(UserManager.class);
            return ownerProcess() && "leo".equals(Build.DEVICE)
                    && users != null && users.isUserUnlocked()
                    && keyguard != null && !keyguard.isDeviceLocked();
        } catch (RuntimeException e) { return false; }
    }

    // Public SDK only. No logcat dump on every one-second status poll.
    private String halRead() {
        return audio.getParameters("leo_hifi_status");
    }

    private int halWrite(String keyValuePairs) {
        WRITE_STATUS_UNAVAILABLE = true;
        audio.setParameters(keyValuePairs);
        return 0; // Transport attempt only. RequestGate readback carries proof.
    }

    // ---- transaction ------------------------------------------------------

    private void request(int kind, int value) {
        main.post(() -> {
            boolean waitingForRead = busy && !state.pending && !expired;
            if (!LeoHifiRequestGate.canStart(state, now(), permitted(),
                    busy && !waitingForRead, kind, value) || deferredUserRequest != null) {
                if (!busy) publish(state.withReason("request_rejected"));
                return;
            }
            restoreAttempted = true;
            LeoHifiRequestGate intent = new LeoHifiRequestGate(kind, value, state, now());
            if (waitingForRead) {
                deferredUserRequest = intent;
                publish(state.withPending(true, "waiting_for_status"));
            } else begin(intent);
        });
    }

    private void begin(LeoHifiRequestGate request) {
        if (busy || !ownerProcess() || !"leo".equals(Build.DEVICE)) return;
        main.removeCallbacks(poll); busy = true; expired = false;
        final long id = ++operation, started = now();
        if (request != null) publish(state.withPending(true, "requesting"));
        final Runnable timeout = () -> {
            if (busy && operation == id) {
                expired = true;
                deferredUserRequest = null;
                publish(LeoHifiState.unavailable("timeout_reconcile_required", now()));
            }
        };
        main.postDelayed(timeout, LeoHifiState.MAX_AGE_MS);
        worker.post(() -> {
            int result = 0;
            LeoHifiState after;
            try {
                if (request != null) {
                    LeoHifiState before = LeoHifiState.parse(halRead(), now());
                    // Recheck immediately before Binder; never replay a queued write after unlock/user changes.
                    boolean allowed = request.kind != LeoHifiRequestGate.MODE || permitted();
                    if (!allowed || now() - request.startedAt >= LeoHifiState.MAX_AGE_MS
                            || before.session != request.session || before.generation != request.generation
                            || !LeoHifiRequestGate.canStart(before, now(), true, false, request.kind, request.value)) {
                        result = -1;
                    } else result = halWrite(request.parameter());
                }
                after = LeoHifiState.parse(halRead(), now());
            } catch (RuntimeException | LinkageError e) {
                result = -1; after = LeoHifiState.unavailable("audio_service_error", now());
            }
            final int code = result;
            final LeoHifiState snapshot = after;
            main.post(() -> {
                main.removeCallbacks(timeout);
                if (id != operation) return;
                busy = false;
                if (expired || now() - started >= LeoHifiState.MAX_AGE_MS) {
                    deferredUserRequest = null;
                    publish(LeoHifiState.unavailable("reconciling", now()));
                    main.post(poll); return; // Never acknowledge a late operation.
                }
                boolean changed = snapshot.available && session != 0 && session != snapshot.session;
                if (snapshot.available) session = snapshot.session;
                if (changed) {
                    deferredUserRequest = null;
                    restoreAttempted = false;
                    publish(LeoHifiState.unavailable("audio_service_restarted", now()));
                } else {
                    boolean accepted = request != null && request.accepts(code, snapshot, now());
                    if (accepted && request.kind == LeoHifiRequestGate.MODE)
                        prefs.edit().putBoolean(SAVED, request.value == 1).apply();
                    publish(request != null && !accepted ? snapshot.withReason("request_failed") : snapshot);
                    // Never retry a refused DAC/guard write in a tight loop.
                    if (request != null && !accepted && request.kind == LeoHifiRequestGate.VOLUME
                            && desiredLevel == request.value) desiredLevel = -1;
                    if (request == null && deferredUserRequest != null) {
                        LeoHifiRequestGate queued = deferredUserRequest;
                        deferredUserRequest = null;
                        if (queued.session == snapshot.session && queued.generation == snapshot.generation
                                && now() >= queued.startedAt
                                && now() - queued.startedAt < LeoHifiState.MAX_AGE_MS
                                && LeoHifiRequestGate.canStart(snapshot, now(), permitted(), false,
                                        queued.kind, queued.value)) {
                            begin(queued); return;
                        }
                        publish(snapshot.withReason("request_rejected"));
                    }
                    if (request == null && snapshot.available && snapshot.supported
                            && !restoreAttempted && permitted()) {
                        restoreAttempted = true;
                        boolean saved = prefs.getBoolean(SAVED, false);
                        if (snapshot.requested != saved) {
                            begin(new LeoHifiRequestGate(LeoHifiRequestGate.MODE, saved ? 1 : 0, snapshot, now()));
                            return;
                        }
                    }
                }
                if (desiredLevel == snapshot.volumeUser && request != null
                        && request.kind == LeoHifiRequestGate.VOLUME && request.value == desiredLevel)
                    desiredLevel = -1;
                if (pump()) return;
                if (interactive) main.postDelayed(poll, 1000);
            });
        });
    }

    private void publish(LeoHifiState snapshot) {
        state = snapshot; main.removeCallbacks(expiry);
        if (snapshot.available) main.postDelayed(expiry,
                Math.max(0, snapshot.observedAtElapsedMs + LeoHifiState.MAX_AGE_MS + 1 - now()));
        for (Callback cb : new ArrayList<>(callbacks)) {
            try { cb.onStateChanged(snapshot); }
            catch (RuntimeException e) { Log.w(TAG, "UI callback failed", e); }
        }
    }
}
