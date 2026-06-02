package com.test.minimal;

import android.app.Application;
import android.util.Log;

/**
 * 最小测试 Application — 无加固、无签名校验、无外部依赖。
 * 用来验证 Stub + LoaderFactory 的 ClassLoader 替换是否能跑通。
 */
public class MinimalApp extends Application {

    private static final String TAG = "MinimalApp";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "=== MinimalApp.onCreate() 成功执行! ===");
        Log.d(TAG, "  packageName=" + getPackageName());
        Log.d(TAG, "  dataDir=" + getDataDir());
        Log.d(TAG, "  classLoader=" + getClassLoader().getClass().getName());
        Log.d(TAG, "  application=" + this.getClass().getName());
    }

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        Log.d(TAG, "=== MinimalApp.attachBaseContext() ===");
        Log.d(TAG, "  base.packageName=" + base.getPackageName());
        Log.d(TAG, "  base.classLoader=" + base.getClassLoader().getClass().getName());
    }
}
