package com.test.minimal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class MainActivityPr9ProviderProbeTest {

    @Test
    void mainActivityRunsPr9ProviderMethodProbeFromLaunchPath() throws IOException {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/test/minimal/MainActivity.java")),
            StandardCharsets.UTF_8
        );

        assertTrue(source.contains(".authority(GUEST_PROVIDER_AUTHORITY)"));
        assertFalse(source.contains(".authority(hostPackageName + \".multiapp.provider.stub\")"));
        assertTrue(source.contains("PROVIDER_PROBE_FILE"));
        assertTrue(source.contains("provider.openFilePayload: "));
        assertTrue(source.contains("provider.openAssetFilePayload: "));
        assertTrue(source.contains("provider.openTypedAssetFilePayload: "));
        assertTrue(source.contains("readProviderPayload("));
        assertTrue(source.contains("getContentResolver().query(uri, null, null, null, null)"));
        assertTrue(source.contains("getContentResolver().insert(uri, values)"));
        assertTrue(source.contains("getContentResolver().update(uri, values, null, null)"));
        assertTrue(source.contains("getContentResolver().delete(uri, null, null)"));
        assertTrue(source.contains("getContentResolver().bulkInsert(uri, new ContentValues[]"));
        assertTrue(source.contains("getContentResolver().call(uri, \"probeCall\", uri.toString(), new Bundle())"));
        assertTrue(source.contains("getContentResolver().openFileDescriptor(uri, \"r\")"));
        assertTrue(source.contains("getContentResolver().openAssetFileDescriptor(uri, \"r\")"));
        assertTrue(source.contains("getContentResolver().openTypedAssetFileDescriptor(uri, \"*/*\", null)"));
    }
}
