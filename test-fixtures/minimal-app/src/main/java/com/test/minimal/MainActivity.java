package com.test.minimal;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 最小测试 Activity — 纯代码布局，零资源依赖。
 * 显示关键运行时信息，验证 ClassLoader 替换是否成功。
 */
public class MainActivity extends Activity {

    private static final String TAG = "MinimalApp";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "=== MainActivity.onCreate() ===");

        // 纯代码布局，不依赖任何资源
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        addText(layout, "✅ MinimalTest 启动成功!", 24, 0xFF4CAF50);
        addText(layout, "", 8, 0);

        StringBuilder info = new StringBuilder();
        info.append("packageName: ").append(getPackageName()).append("\n");
        info.append("application class: ").append(getApplication().getClass().getName()).append("\n");
        info.append("classLoader: ").append(getClassLoader().getClass().getName()).append("\n");
        info.append("classLoader.parent: ").append(getClassLoader().getParent() != null ? getClassLoader().getParent().getClass().getName() : "null").append("\n");
        info.append("dataDir: ").append(getDataDir().getAbsolutePath()).append("\n");
        info.append("processName: ").append(android.os.Process.myPid()).append(" (pid)").append("\n");
        info.append("taskId: ").append(getTaskId()).append("\n");

        // 测试能否加载自己的类
        try {
            Class.forName("com.test.minimal.MinimalApp");
            info.append("\n✅ Class.forName(MinimalApp) 成功\n");
        } catch (ClassNotFoundException e) {
            info.append("\n❌ Class.forName(MinimalApp) 失败: ").append(e.getMessage()).append("\n");
        }

        addText(layout, info.toString(), 16, 0xFF333333);

        // 测试能否访问资源（如果资源系统工作的话）
        try {
            String label = getApplicationInfo().loadLabel(getPackageManager()).toString();
            addText(layout, "\napp label: " + label, 16, 0xFF666666);
        } catch (Exception e) {
            addText(layout, "\n⚠ 无法读取 app label: " + e.getMessage(), 16, 0xFFFF9800);
        }

        addText(layout, "\n如果看到这个页面，说明 Stub + LoaderFactory 的核心链路是通的。", 14, 0xFF999999);

        scroll.addView(layout);
        setContentView(scroll);

        Log.d(TAG, "=== MainActivity.onCreate() 完成 ===");
    }

    private void addText(LinearLayout layout, String text, float sizeSp, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        if (color != 0) tv.setTextColor(color);
        layout.addView(tv);
    }
}
