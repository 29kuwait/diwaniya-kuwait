package com.diwaniya.kuwait.lite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the bar when the box powers on (unless the user switched it off). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (BarService.isEnabled(context) && !BarService.running) {
            context.startService(new Intent(context, BarService.class));
        }
    }
}
