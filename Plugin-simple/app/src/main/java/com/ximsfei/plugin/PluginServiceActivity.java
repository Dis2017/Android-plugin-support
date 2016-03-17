package com.ximsfei.plugin;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.app.Activity;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;

public class PluginServiceActivity extends Activity {

    final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            IPluginAidlInterface my = (IPluginAidlInterface) iBinder;
            try {
                my.show();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {

        }
    };
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_service);
        findViewById(R.id.start).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startService(new Intent(PluginServiceActivity.this, PluginService.class));
            }
        });

        findViewById(R.id.stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopService(new Intent(PluginServiceActivity.this, PluginService.class));
            }
        });

        findViewById(R.id.bind).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                bindService(new Intent(PluginServiceActivity.this, PluginService.class),
                        mServiceConnection, Context.BIND_AUTO_CREATE);
            }
        });

        findViewById(R.id.unbind).setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
                unbindPluginService(mServiceConnection);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindPluginService(mServiceConnection);
    }

    private void unbindPluginService(ServiceConnection sc) {
        try {
            unbindService(sc);
        } catch (Exception e) {
            Log.e("plugin", PluginServiceActivity.class.getSimpleName() + " unbindPluginService ");
        }
    }

}
