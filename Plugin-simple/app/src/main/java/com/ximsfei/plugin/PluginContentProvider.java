package com.ximsfei.plugin;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

public class PluginContentProvider extends ContentProvider {
    public PluginContentProvider() {
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        // Implement this to handle requests to delete one or more rows.
        Log.e("plugin", PluginContentProvider.class.getSimpleName() + ", delete Uri = " + uri);
        Toast.makeText(getContext(),
                PluginContentProvider.class.getSimpleName() + ", delete Uri = " + uri,
                Toast.LENGTH_SHORT).show();
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        // TODO: Implement this to handle requests for the MIME type of the data
        // at the given URI.
        Log.e("plugin", PluginContentProvider.class.getSimpleName() + ", getType Uri = " + uri);
        return "dynamic content";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // TODO: Implement this to handle requests to insert a new row.
        Log.e("plugin", PluginContentProvider.class.getSimpleName() + ", insert Uri = " + uri);
        Toast.makeText(getContext(),
                PluginContentProvider.class.getSimpleName() + ", insert Uri = " + uri,
                Toast.LENGTH_SHORT).show();
        return uri;
    }

    @Override
    public boolean onCreate() {
        // TODO: Implement this to initialize your content provider on startup.
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        // TODO: Implement this to handle query requests from clients.
        Log.e("plugin", PluginContentProvider.class.getSimpleName() + ", query Uri = " + uri);
        Toast.makeText(getContext(),
                PluginContentProvider.class.getSimpleName() + ", query Uri = " + uri,
                Toast.LENGTH_SHORT).show();
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        // TODO: Implement this to handle requests to update one or more rows.
        Log.e("plugin", PluginContentProvider.class.getSimpleName() + ", update Uri = " + uri);
        Toast.makeText(getContext(),
                PluginContentProvider.class.getSimpleName() + ", update Uri = " + uri,
                Toast.LENGTH_SHORT).show();
        return 0;
    }
}
