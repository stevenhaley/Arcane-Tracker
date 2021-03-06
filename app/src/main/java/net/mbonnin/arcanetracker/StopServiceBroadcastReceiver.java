package net.mbonnin.arcanetracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * Created by martin on 10/24/16.
 */

public class StopServiceBroadcastReceiver extends BroadcastReceiver {
    public static final String ACTION = "net.mbonnin.arcanetracker.StopServiceBroadcastReceiver";

    public static Intent getIntent() {
        Intent intent = new Intent();
        intent.setAction(ACTION);

        return intent;
    }

    public static void init() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION);
        HDTApplication.Companion.getContext().registerReceiver(new StopServiceBroadcastReceiver(), filter);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Utils.INSTANCE.exitApp();
    }
}
