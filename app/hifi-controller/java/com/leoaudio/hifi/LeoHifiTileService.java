package com.leoaudio.hifi.singlevolume;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.SystemClock;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Public-API replacement for com.android.systemui.qs.tiles.LeoHifiTile.
 *
 * The state-to-tile mapping in applyState() is a transcription of the original
 * handleUpdateState(): same branch order, same reason/effective strings, same freshness
 * gate. Only the framework surface differs — QSTileImpl/QSHost/Dependency are SystemUI
 * internals and are replaced by TileService/Tile.
 *
 * Long press opens HiFi mode control and read-only DAC status.
 */
public final class LeoHifiTileService extends TileService implements LeoHifiController.Callback {

    private LeoHifiController controller;

    private boolean onTargetDevice() { return "leo".equals(Build.DEVICE); }

    @Override
    public void onStartListening() {
        super.onStartListening();
        LeoHifiMonitorService.start(this);
        controller = LeoHifiController.get(this);
        controller.addCallback(this);
        controller.refresh();
        applyState(controller.getState());
    }

    @Override
    public void onStopListening() {
        if (controller != null) controller.removeCallback(this);
        super.onStopListening();
    }

    @Override
    public void onClick() {
        LeoHifiState state = controller == null ? null : controller.getState();
        if (state == null || !state.available || !state.supported || state.pending
                || !state.isFresh(SystemClock.elapsedRealtime())) {
            return;
        }
        // Re-read inside the unlock callback exactly as the original did: the intent is
        // only honoured against a state that is still fresh at the moment of the write.
        unlockAndRun(() -> {
            LeoHifiState fresh = controller.getState();
            if (fresh != null && fresh.available && fresh.supported && !fresh.pending
                    && fresh.isFresh(SystemClock.elapsedRealtime())) {
                controller.requestEnabled(!fresh.requested);
            }
        });
    }

    @Override
    public void onStateChanged(LeoHifiState state) { applyState(state); }

    private void applyState(LeoHifiState hifiState) {
        Tile tile = getQsTile();
        if (tile == null) return;
        long now = SystemClock.elapsedRealtime();

        tile.setLabel(getString(R.string.leo_hifi_label));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_leo_hifi));
        tile.setContentDescription(getString(R.string.leo_hifi_label));

        if (!onTargetDevice() || hifiState == null || !hifiState.available || !hifiState.supported) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setSubtitle(getString(R.string.leo_hifi_unavailable));
            tile.updateTile();
            return;
        }

        boolean fresh = hifiState.isFresh(now);
        int active = hifiState.requested ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;

        if (hifiState.pending || !fresh) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setSubtitle(getString(R.string.leo_hifi_waiting));
        } else if ("hal_error".equals(hifiState.reason) || "request_failed".equals(hifiState.reason)
                || "request_rejected".equals(hifiState.reason)
                || "hifi_degraded".equals(hifiState.effective) || "error_fallback".equals(hifiState.effective)) {
            tile.setState(active);
            tile.setSubtitle(getString(R.string.leo_hifi_error));
        } else if (hifiState.active) {
            tile.setState(active);
            tile.setSubtitle(getString(R.string.leo_hifi_active, hifiState.rateLabel()));
        } else {
            tile.setState(active);
            tile.setSubtitle(getString(hifiState.requested
                    ? R.string.leo_hifi_standby : R.string.leo_hifi_off));
        }
        tile.setContentDescription(getString(R.string.leo_hifi_label) + ", " + tile.getSubtitle());
        tile.updateTile();
    }
}
