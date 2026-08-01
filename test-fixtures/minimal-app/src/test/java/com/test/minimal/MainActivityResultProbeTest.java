package com.test.minimal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class MainActivityResultProbeTest {

    @Test
    void fixtureExecutesAndPersistsARealActivityResultRoundTrip() throws IOException {
        String main = source("MainActivity.java");
        String second = source("SecondActivity.java");

        assertTrue(main.contains("startActivityForResult(resultIntent, REQUEST_ACTIVITY_RESULT_PROBE)"));
        assertTrue(main.contains("protected void onActivityResult(int requestCode, int resultCode, Intent data)"));
        assertTrue(main.contains("openFileOutput(ACTIVITY_RESULT_PROBE_FILE, MODE_PRIVATE)"));
        assertTrue(second.contains("setResult(RESULT_OK, result)"));
        assertTrue(second.contains("ACTION_ACTIVITY_RESULT_RESPONSE"));
        assertTrue(second.contains("finish()"));
    }

    private String source(String fileName) throws IOException {
        return new String(
            Files.readAllBytes(Paths.get("src/main/java/com/test/minimal/" + fileName)),
            StandardCharsets.UTF_8
        );
    }
}
