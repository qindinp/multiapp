package com.multiapp.core.loader;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class QqReaderSignCompat {
    private static final String TAG = "QqReaderSignCompat";
    private static final String MD5_KEY = "51076a5fd0b1fd440c06277855f27311";
    private static final String FOCK_RT_KEY = "3c2c606c-3405-45db-a39d-7d24bdfef0cb";
    private static volatile boolean fockRtSetupTried;

    private QqReaderSignCompat() {
    }

    public static String sign(String value) {
        String normalized = value == null ? "" : value;
        String fockRtSign = tryFockRtSign(normalized);
        if (fockRtSign != null && !fockRtSign.isEmpty()) {
            Log.i(TAG, "sign via FockRT len=" + normalized.length()
                    + " sample=" + sample(normalized)
                    + " resultLen=" + fockRtSign.length());
            return fockRtSign;
        }

        try {
            String raw = md5(normalized);
            String valueThenKey = md5(normalized + MD5_KEY);
            String keyThenValue = md5(MD5_KEY + normalized);
            Log.w(TAG, "sign FockRT unavailable len=" + normalized.length()
                    + " sample=" + sample(normalized)
                    + " md5=" + raw
                    + " valueKey=" + valueThenKey
                    + " keyValue=" + keyThenValue);
            Log.w(TAG, "returning md5 fallback");
            return raw;
        } catch (Throwable throwable) {
            Log.w(TAG, "sign failed len=" + normalized.length(), throwable);
            return "";
        }
    }

    private static String tryFockRtSign(String value) {
        try {
            Class<?> fockRtClass = Class.forName("com.yuewen.fockrt.FockRT");
            ensureFockRtSetup(fockRtClass);
            Method sn = fockRtClass.getDeclaredMethod("sn", String.class);
            Object result = sn.invoke(null, value);
            if (result instanceof String) {
                return (String) result;
            }
            Log.w(TAG, "FockRT.sn returned non-string: " + result);
        } catch (Throwable throwable) {
            Log.w(TAG, "FockRT.sign failed len=" + value.length(), throwable);
        }
        return null;
    }

    private static void ensureFockRtSetup(Class<?> fockRtClass) {
        if (fockRtSetupTried) {
            return;
        }
        synchronized (QqReaderSignCompat.class) {
            if (fockRtSetupTried) {
                return;
            }
            fockRtSetupTried = true;
            try {
                Field fileManager = fockRtClass.getDeclaredField("fileManager");
                fileManager.setAccessible(true);
                if (fileManager.get(null) != null) {
                    Log.i(TAG, "FockRT already setup");
                    return;
                }
            } catch (Throwable throwable) {
                Log.w(TAG, "FockRT fileManager check failed", throwable);
            }

            try {
                Object application = currentApplication();
                if (application == null) {
                    Log.w(TAG, "FockRT setup skipped: currentApplication is null");
                    return;
                }
                Method setup = fockRtClass.getDeclaredMethod(
                        "setup",
                        android.content.Context.class,
                        String.class,
                        boolean.class);
                setup.invoke(null, application, FOCK_RT_KEY, false);
                Log.i(TAG, "FockRT setup invoked");
            } catch (Throwable throwable) {
                Log.w(TAG, "FockRT setup failed", throwable);
            }
        }
    }

    private static Object currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            return currentApplication.invoke(null);
        } catch (Throwable throwable) {
            Log.w(TAG, "currentApplication failed", throwable);
            return null;
        }
    }

    private static String md5(String input) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            builder.append(String.format("%02x", b & 0xff));
        }
        return builder.toString();
    }

    private static String sample(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int head = Math.min(16, value.length());
        int tail = Math.min(16, value.length() - head);
        if (tail <= 0) {
            return value.substring(0, head);
        }
        return value.substring(0, head) + "..." + value.substring(value.length() - tail);
    }
}
