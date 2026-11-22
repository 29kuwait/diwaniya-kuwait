package com.diwaniya.kuwait.lite;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Launcher entry: toggles the bar on/off and remembers the choice across reboots. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // "force_on" lets an install script switch the bar on without toggling it off.
        boolean enable = getIntent().getBooleanExtra("force_on", false) || !BarService.running;
        BarService.setEnabled(this, enable);
        Intent i = new Intent(this, BarService.class);
        if (enable) {
            startService(i);
            Toast.makeText(this, "تم تشغيل الشريط البسيط", Toast.LENGTH_SHORT).show();
        } else {
            stopService(i);
            Toast.makeText(this, "تم إيقاف الشريط البسيط", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
