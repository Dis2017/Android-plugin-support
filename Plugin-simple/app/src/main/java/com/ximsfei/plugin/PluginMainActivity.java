package com.ximsfei.plugin;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

public class PluginMainActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "PluginMainActivity";
    private PluginBroadcastReceiver mReceiver;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);
        mReceiver = new PluginBroadcastReceiver();
        findViewById(R.id.startActivity).setOnClickListener(this);
        findViewById(R.id.registerReceiver).setOnClickListener(this);
        findViewById(R.id.unregisterReceiver).setOnClickListener(this);
        findViewById(R.id.sendBroadcast).setOnClickListener(this);
        findViewById(R.id.sendStaticBroadcast).setOnClickListener(this);
        findViewById(R.id.query).setOnClickListener(this);
        findViewById(R.id.insert).setOnClickListener(this);
        findViewById(R.id.update).setOnClickListener(this);
        findViewById(R.id.delete).setOnClickListener(this);
    }

    @Override
    public void onClick(View view) {
        Intent intent;
        Uri uri = Uri.parse("content://dynamic/content/1");
        switch(view.getId()) {
            case R.id.startActivity:
                startActivity(new Intent(PluginMainActivity.this, PluginServiceActivity.class));
                break;
            case R.id.registerReceiver:
                IntentFilter intentFilter = new IntentFilter();
                intentFilter.addAction(PluginBroadcastReceiver.MY_ACTION);
                registerReceiver(mReceiver, intentFilter);
                break;
            case R.id.unregisterReceiver:
                unregisterPluginReceiver();
                break;
            case R.id.sendBroadcast:
                intent = new Intent();
                intent.setAction(PluginBroadcastReceiver.MY_ACTION);
                sendBroadcast(intent);
                break;
            case R.id.sendStaticBroadcast:
                intent = new Intent();
                intent.setAction(PluginStaticBroadReceiver.MY_STATIC_ACTION);
                sendBroadcast(intent);
                break;
            case R.id.query:
                getContentResolver().query(uri, null, null, null, null);
                break;
            case R.id.insert:
                getContentResolver().insert(uri, null);
                break;
            case R.id.update:
                getContentResolver().update(uri, null, null, null);
                break;
            case R.id.delete:
                getContentResolver().delete(uri, null, null);
                break;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterPluginReceiver();
    }

    private void unregisterPluginReceiver() {
        try {
            unregisterReceiver(mReceiver);
        } catch (Exception e) {
            Log.e("plugin", PluginMainActivity.class.getSimpleName() + " unregisterPluginReceiver");
        }
    }
}
