package com.test.minimal;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
        Log.d(TAG, "=== provider probe === status=INSERT_OK uri=" + uri);
        return uri.buildUpon().appendPath("inserted").build();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        Log.d(TAG, "=== provider probe === status=DELETE_OK uri=" + uri);
        return 1;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        Log.d(TAG, "=== provider probe === status=UPDATE_OK uri=" + uri);
        return 1;
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues[] values) {
        int count = values != null ? values.length : 0;
        Log.d(TAG, "=== provider probe === status=BULK_INSERT_OK count=" + count + " uri=" + uri);
        return count;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        result.putString("providerCallStatus", "CALL_OK");
        result.putString("providerCallMethod", method);
        result.putString("providerCallArg", arg != null ? arg : "");
        Log.d(TAG, "=== provider probe === status=CALL_OK method=" + method);
        return result;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Log.d(TAG, "=== provider probe === status=OPEN_FILE_OK uri=" + uri);
        return ParcelFileDescriptor.open(providerPayloadFile(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        Log.d(TAG, "=== provider probe === status=OPEN_ASSET_FILE_OK uri=" + uri);
        return new AssetFileDescriptor(openFile(uri, mode), 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts) throws FileNotFoundException {
        Log.d(TAG, "=== provider probe === status=OPEN_TYPED_ASSET_FILE_OK mimeTypeFilter=" + mimeTypeFilter + " uri=" + uri);
        return openAssetFile(uri, "r");
    }

    private File providerPayloadFile() throws FileNotFoundException {
        if (getContext() == null) {
            throw new FileNotFoundException("provider context missing");
        }
        File file = new File(getContext().getFilesDir(), "probe-provider-open.txt");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("provider-open-ok".getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            FileNotFoundException wrapped = new FileNotFoundException(error.getMessage());
            wrapped.initCause(error);
            throw wrapped;
        }
        return file;
    }
}
