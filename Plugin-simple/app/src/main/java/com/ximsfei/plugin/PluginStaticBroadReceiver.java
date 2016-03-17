package com.ximsfei.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * Created by pengfenx on 3/16/2016.
 */
public class PluginStaticBroadReceiver extends BroadcastReceiver {
    public static final String MY_STATIC_ACTION = "com.ximsfei.plugin.PluginStaticBroadReceiver";
    @Override
    public void onReceive(Context context, Intent intent) {
        if (MY_STATIC_ACTION.equals(intent.getAction())) {
            Log.e("plugin", PluginStaticBroadReceiver.class.getSimpleName()
                    + " Action = " + intent.getAction());
            Toast.makeText(context,
                    PluginStaticBroadReceiver.class.getSimpleName() + "onReceive Action = "
                            + intent.getAction(),
                    Toast.LENGTH_SHORT).show();
        }
    }
}
