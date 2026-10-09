package com.leoaudio.hifi.singlevolume;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** HiFi mode control and read-only hardware status. Audio volume belongs to Android. */
public final class LeoHifiVolumeActivity extends Activity implements LeoHifiController.Callback {
    private LeoHifiController controller;
    private AlertDialog dialog;
    private Button toggle;
    private TextView status, readback;
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LeoHifiMonitorService.start(this);
        controller = LeoHifiController.get(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density + 0.5f);
        layout.setPadding(padding, padding, padding, padding);
        TextView instruction = new TextView(this);
        instruction.setText(R.string.leo_hifi_volume_warning);
        layout.addView(instruction);
        status = new TextView(this); layout.addView(status);
        readback = new TextView(this); layout.addView(readback);
        toggle = new Button(this); layout.addView(toggle);
        toggle.setOnClickListener(view -> controller.requestEnabled(!controller.getState().requested));
        dialog = new AlertDialog.Builder(this).setTitle(R.string.leo_hifi_volume_title)
                .setView(layout).setNegativeButton(android.R.string.ok, (d, which) -> d.dismiss())
                .setOnDismissListener(d -> finish()).create();
        controller.addCallback(this);
        dialog.show();
        onStateChanged(controller.getState());
    }
    @Override protected void onDestroy() {
        if (controller != null) controller.removeCallback(this);
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        super.onDestroy();
    }
    @Override public void onStateChanged(LeoHifiState s) {
        if (toggle == null || s == null) return;
        boolean fresh = s.available && s.isFresh(SystemClock.elapsedRealtime());
        toggle.setEnabled(fresh && s.supported && !s.pending);
        toggle.setText(s.requested ? R.string.leo_hifi_disable : R.string.leo_hifi_enable);
        int label = s.pending ? R.string.leo_hifi_waiting : !fresh ? R.string.leo_hifi_unavailable
                : "hal_error".equals(s.reason) || "request_failed".equals(s.reason)
                || "request_rejected".equals(s.reason) ? R.string.leo_hifi_error
                : s.active ? R.string.leo_hifi_active : s.requested ? R.string.leo_hifi_standby : R.string.leo_hifi_off;
        status.setText(label == R.string.leo_hifi_active
                ? getString(label, s.rateLabel()) : getString(label));
        if (fresh && s.schema5) {
            readback.setText(getString(R.string.leo_hifi_dac_readback,
                    s.active ? (s.volumeLeft - 255) / 2 : s.volumeUser - 60, s.volumeLeft, s.volumeRight));
            return;
        }
        readback.setText(fresh ? getString(s.volumeLeft == 205 && s.volumeRight == 205
                ? R.string.leo_hifi_volume_fixed : R.string.leo_hifi_volume_readback,
                s.volumeLeft, s.volumeRight) : getString(R.string.leo_hifi_no_readback));
    }
}
