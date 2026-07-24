package cn.pashiguoke.subscreen;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import cn.pashiguoke.subscreen.util.ShizukuHelper;

/**
 * 应用列表界面 —— 在外屏上列出已安装的第三方应用，点击后通过 Shizuku
 * 以 shell 权限启动到外屏（绕过 canHostTasks=false 限制）。
 */
public class DisplayActivity extends SubBaseActivity {
    private static final String TAG = "DisplayActivity";
    Display[] displays;
    int targetDisplayId = Display.INVALID_DISPLAY;
    ShizukuHelper shizukuHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_display);

        shizukuHelper = new ShizukuHelper(this);

        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            if (km.isDeviceLocked()) {
                findViewById(R.id.appListView).setVisibility(View.GONE);
                findViewById(R.id.infoText).setVisibility(View.VISIBLE);
            } else {
                findViewById(R.id.infoText).setVisibility(View.GONE);
                findViewById(R.id.appListView).setVisibility(View.VISIBLE);
            }
        }

        Button backBtn = findViewById(R.id.backButton);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                DisplayActivity.this.finish();
            }
        });

        ListView appList = findViewById(R.id.appListView);

        // 获取所有显示器 —— 修复原始代码中直接访问 displays[1] 的崩溃问题
        DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        displays = displayManager.getDisplays();

        // 确定目标显示器：优先选择非默认显示器（外屏），
        // 如果只有 1 个显示器，回退到当前显示器。
        for (Display display : displays) {
            Log.i(TAG, "Available display: id=" + display.getDisplayId()
                    + ", state=" + display.getState());
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                targetDisplayId = display.getDisplayId();
                break;
            }
        }
        if (targetDisplayId == Display.INVALID_DISPLAY && displays.length > 0) {
            // 只有 1 个显示器时，使用默认显示器
            targetDisplayId = displays[0].getDisplayId();
            Log.w(TAG, "Only 1 display found, using displayId=" + targetDisplayId);
        }

        // 查询已安装的第三方应用
        // Vivo Android 16 对普通 App 有包可见性限制，getInstalledApplications() 只返回自身。
        // 通过 Shizuku 执行 `pm list packages -3` 以 shell 权限获取完整包名列表。
        PackageManager pm = getPackageManager();
        List<String> packageNames = shizukuHelper.getInstalledPackageNamesViaShizuku();

        if (packageNames == null || packageNames.isEmpty()) {
            // Shizuku 不可用时回退到普通查询（结果有限但不会崩溃）
            Log.w(TAG, "Shizuku package query failed, falling back to normal query");
            @SuppressLint("QueryPermissionsNeeded")
            List<ApplicationInfo> fallback = pm.getInstalledApplications(0);
            packageNames = new ArrayList<>();
            for (ApplicationInfo info : fallback) {
                if ((info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    packageNames.add(info.packageName);
                }
            }
        }
        Log.i(TAG, "Total packages retrieved: " + packageNames.size());

        AppAdapter appAdapter = new AppAdapter(this, targetDisplayId, shizukuHelper);
        for (String pkg : packageNames) {
            appAdapter.addPackage(pkg);
        }
        Log.i(TAG, "Apps shown: " + appAdapter.getCount());
        appAdapter.notifyDataSetChanged();

        appList.setAdapter(appAdapter);

        Log.i(TAG, "DisplayActivity ready: " + appAdapter.getCount()
                + " apps, targetDisplayId=" + targetDisplayId);
    }
}

class AppAdapter extends BaseAdapter {
    private final Context context;
    private final List<String> packageNames;
    private final int targetDisplayId;
    private final ShizukuHelper shizukuHelper;

    public AppAdapter(Context context, int targetDisplayId, ShizukuHelper shizukuHelper) {
        this.context = context;
        this.packageNames = new ArrayList<>();
        this.targetDisplayId = targetDisplayId;
        this.shizukuHelper = shizukuHelper;
    }

    public void addPackage(String packageName) {
        this.packageNames.add(packageName);
    }

    @Override
    public int getCount() {
        return this.packageNames.size();
    }

    @Override
    public String getItem(int position) {
        return packageNames.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (position >= packageNames.size())
            return null;
        View view = View.inflate(context, R.layout.layout_applist, null);
        TextView nameView = view.findViewById(R.id.name);
        TextView packageView = view.findViewById(R.id.packagename);
        ImageView iconView = view.findViewById(R.id.iconview);

        final String packageName = getItem(position);
        PackageManager pm = context.getPackageManager();

        // 尝试获取应用名称和图标，可能因包可见性限制失败
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            nameView.setText(label != null ? label : packageName);
            iconView.setImageDrawable(pm.getApplicationIcon(info));
        } catch (Exception e) {
            // 包可见性限制导致无法获取信息，使用包名和默认图标
            nameView.setText(packageName);
            iconView.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        packageView.setText(packageName);

        final int displayId = targetDisplayId;
        view.setOnClickListener(new View.OnClickListener() {
            @RequiresApi(api = Build.VERSION_CODES.O)
            @Override
            public void onClick(View view) {
                // 在后台线程执行启动操作，避免 UI 冻结
                // launchPackageOnDisplay 包含多次 shell 命令和 sleep，
                // 整个过程约 4-5 秒
                Toast.makeText(context, "正在启动 " + packageName + "...", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    boolean launched = shizukuHelper.launchPackageOnDisplay(packageName, displayId);
                    view.post(() -> {
                        if (launched) {
                            Log.i("AppAdapter", "Launched " + packageName
                                    + " on display " + displayId);
                        } else {
                            Log.w("AppAdapter", "Failed to launch via Shizuku");
                            Toast.makeText(context, "无法启动此应用", Toast.LENGTH_SHORT).show();
                        }
                    });
                }).start();
            }
        });

        return view;
    }
}
