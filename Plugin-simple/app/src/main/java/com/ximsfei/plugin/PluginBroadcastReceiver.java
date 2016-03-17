package com.ximsfei.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * Created by pengfenx on 3/16/2016.
 */
public class PluginBroadcastReceiver extends BroadcastReceiver {
    public static final String MY_ACTION = "com.ximfei.plugin.MyBroadcastReceiver";
    @Override
    public void onReceive(Context context, Intent intent) {
        if (MY_ACTION.equals(intent.getAction())) {
            Log.e("plugin", PluginBroadcastReceiver.class.getSimpleName()
                    + " Action = " + intent.getAction());
            Toast.makeText(context,
                    PluginBroadcastReceiver.class.getSimpleName() + "onReceive Action = "
                            + intent.getAction(),
                    Toast.LENGTH_SHORT).show();
        }
    }
}
