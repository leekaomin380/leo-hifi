package com.leoaudio.hifi.singlevolume;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** The neutral FGS notification must NEVER claim that HiFi is working.
 * Android 10 ignores timeoutAfter on FGS notifications. The working badge is
 * a separate ordinary notification, with expiry enforced by system_server.
 */
public final class LeoHifiNotifications {
    public static final int MONITOR_ID = 41, ACTIVE_ID = 42;
    private static final String MONITOR = "leo_observer_v1", ACTIVE = "leo_active_v1";
    private final Context context;
    private final NotificationManager manager;

    public LeoHifiNotifications(Context context) {
        this.context = context;
        manager = context.getSystemService(NotificationManager.class);
        NotificationChannel observer = new NotificationChannel(MONITOR,
                context.getString(R.string.leo_monitor_channel), NotificationManager.IMPORTANCE_MIN);
        observer.setSound(null, null);
        manager.createNotificationChannel(observer);
        NotificationChannel active = new NotificationChannel(ACTIVE,
                context.getString(R.string.leo_active_channel), NotificationManager.IMPORTANCE_LOW);
        active.setSound(null, null);
        manager.createNotificationChannel(active);
    }
    private Notification.Builder builder(String channel) {
        Intent open = new Intent(context, LeoHifiVolumeActivity.class);
        PendingIntent intent = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, channel).setContentIntent(intent)
                .setOnlyAlertOnce(true).setShowWhen(false).setLocalOnly(true);
    }
    public Notification observer() {
        return builder(MONITOR).setSmallIcon(R.drawable.ic_leo_observer)
                .setContentTitle(context.getString(R.string.leo_monitor_title))
                .setContentText(context.getString(R.string.leo_monitor_description))
                .setOngoing(true).build();
    }
    public boolean canShowActive() {
        NotificationChannel ch = manager.getNotificationChannel(ACTIVE);
        return manager.areNotificationsEnabled() && ch != null
                && ch.getImportance() >= NotificationManager.IMPORTANCE_LOW;
    }
    public void update(LeoHifiState state, long now) {
        long remaining = state.observedAtElapsedMs + LeoHifiState.MAX_AGE_MS - now;
        if (!state.available || !state.active || state.pending || !state.isFresh(now)
                || remaining <= 0 || !canShowActive()) {
            clearActive(); return;
        }
        manager.notify(ACTIVE_ID, builder(ACTIVE).setSmallIcon(R.drawable.ic_leo_hifi)
                .setContentTitle(context.getString(R.string.leo_hifi_active, state.rateLabel()))
                .setContentText(context.getString(R.string.leo_active_description))
                .setOngoing(true).setTimeoutAfter(remaining).build());
    }
    public void clearActive() { manager.cancel(ACTIVE_ID); }
}
