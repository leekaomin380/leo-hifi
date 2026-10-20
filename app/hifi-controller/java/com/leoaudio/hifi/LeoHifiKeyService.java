package com.leoaudio.hifi.singlevolume;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Routes the hardware volume keys to the ESS DAC while hard volume is pinned.
 * Any other time the keys are returned to Android untouched. */
public final class LeoHifiKeyService extends AccessibilityService {
    private LeoVolumeOverlay overlay;
    private boolean consumingDown;

    @Override protected void onServiceConnected() {
        LeoHardVolume.keyServiceConnected = true;
        LeoHifiMonitorService.start(this);
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        LeoHardVolume.keyServiceConnected = false;
        if (overlay != null) overlay.hide();
        // Only a user who switched the service off ends hard volume; an app
        // update or process restart re-binds and must keep the pinned level.
        if (!LeoHardVolume.get(this).keyServiceEnabled())
            LeoHardVolume.get(this).release("key_service_off");
        return super.onUnbind(intent);
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean consumed = consumingDown;
            consumingDown = false;
            return consumed; // keep DOWN/UP pairs together
        }
        int level = LeoHardVolume.get(this).onVolumeKey(code == KeyEvent.KEYCODE_VOLUME_UP);
        consumingDown = level >= 0;
        if (level < 0) return false;
        if (overlay == null) overlay = new LeoVolumeOverlay(this);
        overlay.show(level);
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
