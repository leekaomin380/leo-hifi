package com.leoaudio.fixture;

import android.app.*;
import android.content.Intent;
import android.media.*;
import android.os.*;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/** Temporary, shell-controlled PCM fixture. Never requests focus or changes volume. */
public final class FixtureService extends Service {
    private static final String TAG = "LeoFixture";
    private final Source[] sources = new Source[2];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastError = "none";

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("fixture", "HiFi 验收", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "fixture")
                .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("HiFi 验收音源")
                .setContentText("低幅度 PCM 测试；不改变系统音量").build());
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String command = intent.getStringExtra("command");
        if ("stop-all".equals(command)) {
            close(0); close(1); stopSelf();
        } else if ("stop-music".equals(command)) close(0);
        else if ("stop-aux".equals(command)) close(1);
        else if ("music".equals(command) || "aux".equals(command)) {
            int slot = "music".equals(command) ? 0 : 1;
            int rate = intent.getIntExtra("rate", 48000);
            int duration = Math.max(1, Math.min(120, intent.getIntExtra("seconds", 60)));
            if (rate != 44100 && rate != 48000) lastError = "invalid rate";
            else {
                close(slot);
                try {
                    Source source = new Source(slot, rate);
                    sources[slot] = source;
                    source.start();
                    handler.postDelayed(() -> { if (sources[slot] == source) close(slot); }, duration * 1000L);
                } catch (RuntimeException e) { lastError = e.toString(); Log.e(TAG, lastError); }
            }
        } else if ("raw-hifi-off".equals(command)) {
            // Test-only: switch HiFi off WITHOUT the HiFi app, as an unordered
            // exit would.  Candidate C must keep the standard path silent then.
            AudioManager am = getSystemService(AudioManager.class);
            String st = am.getParameters("leo_hifi_status");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("session:([0-9]+),gen:([0-9]+),").matcher(st == null ? "" : st);
            if (m.find()) {
                am.setParameters("leo_hifi_mode=false;leo_hifi_session=" + m.group(1)
                        + ";leo_hifi_gen=" + m.group(2));
                lastError = "raw-hifi-off sent";
            } else lastError = "no status";
        } else lastError = "unknown command";
        return START_NOT_STICKY;
    }

    private void close(int slot) {
        Source source = sources[slot];
        if (source != null) { source.close(); sources[slot] = null; }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null); close(0); close(1); super.onDestroy();
    }
    @Override protected void dump(FileDescriptor fd, PrintWriter out, String[] args) {
        out.println("test-only; amplitude=0.008 (-41.94 dBFS); no focus/volume changes");
        out.println("last_error=" + lastError);
        try { out.println("hal_status=" + getSystemService(AudioManager.class).getParameters("leo_hifi_status")); }
        catch (RuntimeException e) { out.println("hal_status_error=" + e.toString()); }
        for (int slot = 0; slot < sources.length; slot++) {
            Source source = sources[slot];
            out.println(source == null ? "slot=" + slot + " stopped" : source.status());
        }
    }

    private static final class Source {
        final int slot, rate;
        final AudioTrack track;
        final short[] samples;
        volatile boolean running;
        volatile long writtenFrames;
        volatile int writeError;
        Thread writer;
        Source(int slot, int rate) {
            this.slot = slot; this.rate = rate;
            int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("min buffer " + minimum);
            int buffer = slot == 0 ? Math.max(minimum, rate * 4 / 5) : minimum;
            track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(slot == 0 ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(slot == 0 ? AudioAttributes.CONTENT_TYPE_MUSIC : AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(buffer)
                    .setPerformanceMode(slot == 0 ? AudioTrack.PERFORMANCE_MODE_POWER_SAVING : AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) { track.release(); throw new IllegalStateException("track uninitialized"); }
            // One second repeats at an integral frequency, avoiding discontinuity at the loop boundary.
            samples = new short[rate * 2];
            int frequency = slot == 0 ? 440 : 660;
            for (int frame = 0; frame < rate; frame++) {
                short value = (short)Math.round(32767 * 0.008 * Math.sin(2 * Math.PI * frequency * frame / rate));
                samples[frame * 2] = value; samples[frame * 2 + 1] = value;
            }
        }
        void start() {
            running = true;
            track.play();
            writer = new Thread(() -> {
                int offset = 0;
                while (running) {
                    int count = track.write(samples, offset, Math.min(1024, samples.length - offset), AudioTrack.WRITE_BLOCKING);
                    if (count < 0) { writeError = count; break; }
                    writtenFrames += count / 2;
                    offset = (offset + count) % samples.length;
                }
                running = false;
            }, "LeoFixture-" + slot);
            writer.start();
            Log.i(TAG, "started " + status());
        }
        String status() {
            return "slot=" + slot + " requested_rate=" + rate + " track_rate=" + track.getSampleRate()
                    + " session=" + track.getAudioSessionId() + " performance=" + track.getPerformanceMode()
                    + " running=" + running + " written_frames=" + writtenFrames
                    + " playback_head=" + (track.getPlaybackHeadPosition() & 0xffffffffL)
                    + " underruns=" + track.getUnderrunCount() + " write_error=" + writeError;
        }
        void close() {
            running = false;
            try { track.pause(); track.flush(); } catch (IllegalStateException ignored) {}
            if (writer != null) {
                try { writer.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (writer.isAlive()) Log.e(TAG, "writer failed to terminate for slot " + slot);
            }
            track.release();
            Log.i(TAG, "stopped slot=" + slot + " written_frames=" + writtenFrames + " write_error=" + writeError);
        }
    }
}
