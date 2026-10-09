package com.leoaudio.hifi.singlevolume;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class LeoHifiBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) LeoHifiMonitorService.start(context);
    }
}
