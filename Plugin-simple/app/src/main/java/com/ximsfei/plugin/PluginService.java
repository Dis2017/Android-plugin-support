package com.ximsfei.plugin;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

public class PluginService extends Service {

    private class PluginBind extends IPluginAidlInterface.Stub {

        @Override
        public void show() throws RemoteException {
            Log.e("plugin", "IPluginAidlInterface show() !");
            Toast.makeText(PluginService.this.getApplicationContext(),
                    PluginService.class.getSimpleName() + " IPluginAidlInterface show() !",
                    Toast.LENGTH_SHORT).show();
        }
    }
    public PluginService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        return new PluginBind();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.e("plugin", PluginService.class.getSimpleName() + " onUnbind");
        Toast.makeText(getApplicationContext(),
                PluginService.class.getSimpleName() + " onUnbind",
                Toast.LENGTH_SHORT).show();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        Log.e("plugin", PluginService.class.getSimpleName() + " onDestroy");
        Toast.makeText(getApplicationContext(),
                PluginService.class.getSimpleName() + " onDestroy",
                Toast.LENGTH_SHORT).show();
        super.onDestroy();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.e("plugin", PluginService.class.getSimpleName());
        Toast.makeText(getApplicationContext(),
                PluginService.class.getSimpleName() + " onCreate",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        Log.e("plugin", PluginService.class.getSimpleName() + "onStart()");
        Toast.makeText(getApplicationContext(),
                PluginService.class.getSimpleName() + " onStart",
                Toast.LENGTH_SHORT).show();
    }
}
