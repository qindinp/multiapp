package com.test.minimal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class MainActivityPr8AmsApiProbeTest {

    @Test
    void mainActivityRunsPr8AmsApiProbeFromLaunchPath() throws IOException {
        String source = mainActivitySource();

        assertTrue(
            source.contains("String pr8AmsApiProbe = runPr8AmsApiProbe();"),
            "MainActivity.onCreate must run the PR-8 AMS API probe from the deterministic launch path"
        );
        assertTrue(
            source.contains("registerReceiver(") && source.contains("dynamicReceiver"),
            "PR-8 probe must call Context.registerReceiver(...) with a dynamic receiver"
        );
        assertTrue(
            source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU")
                && source.contains("Context.RECEIVER_NOT_EXPORTED"),
            "PR-8 dynamic receiver registration must be API 33+ compatible and not exported"
        );
        assertTrue(
            source.contains("unregisterReceiver(dynamicReceiver)"),
            "PR-8 dynamic receiver must be unregistered after the probe"
        );
        assertTrue(
            source.contains("sendStickyOrderedBroadcast(stickyOrdered"),
            "PR-8 probe must call Context.sendStickyOrderedBroadcast(...)"
        );
        assertTrue(
            source.contains("runtimeContext.startActivity(missingActivity, new Bundle())"),
            "PR-8 probe must call the Context.startActivity(Intent, Bundle) overload through the hosted runtime context"
        );
        assertTrue(
            source.contains("runtimeContext.startActivities(new Intent[] { first, second })"),
            "PR-8 probe must call the Context.startActivities(...) overload through the hosted runtime context"
        );
        assertTrue(
            source.contains("runtimeContext.startForegroundService(foregroundService)"),
            "PR-8 probe must call Context.startForegroundService(...) so foreground-service proxy/partial evidence is emitted"
        );
        assertTrue(
            source.contains("bindService(") && source.contains("connection") && source.contains("BIND_AUTO_CREATE"),
            "PR-8 probe must call a bindService overload with BIND_AUTO_CREATE"
        );
        assertTrue(
            source.contains("if (bound)") && source.contains("unbindService(connection)"),
            "PR-8 bindService probe must unbind only when bindService returns true"
        );
    }

    private String mainActivitySource() throws IOException {
        return new String(
            Files.readAllBytes(Paths.get("src/main/java/com/test/minimal/MainActivity.java")),
            StandardCharsets.UTF_8
        );
    }
}
