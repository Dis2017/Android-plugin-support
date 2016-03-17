package com.ximsfei.dynamicapk;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.ximsfei.dynamic.DynamicApkManager;

import java.io.File;
import java.util.HashMap;

public class MainActivity extends Activity implements AdapterView.OnItemClickListener {

    private ListView mListView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        File apkFile = new File("/data/local/tmp/dynamicapk");
        DynamicApkManager.getInstance().install(apkFile);

        mListView = (ListView) findViewById(R.id.list);
        mListView.setOnItemClickListener(this);

        findViewById(R.id.get_main_activities).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startService(new Intent(MainActivity.this, HostService.class));
                HashMap activities = DynamicApkManager.getInstance().getMainActivities();
                mListView.setAdapter(new ArrayAdapter<>(MainActivity.this,
                        android.R.layout.simple_list_item_1,
                        activities.keySet().toArray(new String[activities.keySet().size()])));
            }
        });

    }

    private void startActivityByClassName(String clsName) {
        Intent i = new Intent();
        i.setClassName(getPackageName(), clsName);
        startActivity(i);
    }

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
        startActivityByClassName(DynamicApkManager.getInstance().getMainActivity((
                        (TextView) view).getText().toString()).name);
    }
}
