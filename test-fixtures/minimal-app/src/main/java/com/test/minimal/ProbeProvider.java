package com.test.minimal;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

public class ProbeProvider extends ContentProvider {
    private static final String TAG = "MinimalApp";

    @Override
    public boolean onCreate() {
        Log.d(TAG, "=== provider probe === status=CREATED");
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[] { "status", "packageName", "dataDir" });
        cursor.addRow(new Object[] {
            "QUERY_OK",
            getContext() != null ? getContext().getPackageName() : "",
            getContext() != null ? getContext().getDataDir().getAbsolutePath() : ""
        });
        Log.d(TAG, "=== provider probe === status=QUERY_OK uri=" + uri);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.com.test.minimal.probe";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
