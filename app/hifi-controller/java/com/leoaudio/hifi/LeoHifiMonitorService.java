package com.leoaudio.hifi.singlevolume;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

public final class LeoHifiMonitorService extends Service implements LeoHifiController.Callback {
    private LeoHifiNotifications notifications;
    private LeoHifiController controller;
    public static void start(Context context) {
        if (!"leo".equals(Build.DEVICE) || android.os.Process.myUid() / 100000 != 0) return;
        try { context.startForegroundService(new Intent(context, LeoHifiMonitorService.class)); }
        catch (RuntimeException e) { Log.w("LeoHifi", "Monitor unavailable", e); }
    }
    @Override public void onCreate() {
        super.onCreate();
        notifications = new LeoHifiNotifications(this);
        notifications.clearActive();
        startForeground(LeoHifiNotifications.MONITOR_ID, notifications.observer());
        controller = LeoHifiController.get(this);
        controller.addCallback(this);
        LeoHardVolume.get(this); // registers the hard-volume orchestrator
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, filter);
        PowerManager power = getSystemService(PowerManager.class);
        controller.setInteractive(power == null || power.isInteractive());
    }
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            controller.setInteractive(Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
        }
    };
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        controller.refresh();
        return START_STICKY;
    }
    @Override public void onStateChanged(LeoHifiState state) {
        notifications.update(state, SystemClock.elapsedRealtime());
    }
    @Override public void onDestroy() {
        try { unregisterReceiver(screen); } catch (IllegalArgumentException ignored) { }
        if (controller != null) {
            controller.removeCallback(this);
            controller.setInteractive(true); // Tile/activity may still use the shared controller.
        }
        if (notifications != null) notifications.clearActive();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
