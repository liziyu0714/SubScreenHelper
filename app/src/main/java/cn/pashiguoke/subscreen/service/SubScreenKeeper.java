package cn.pashiguoke.subscreen.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import cn.pashiguoke.subscreen.SubActivity;
import cn.pashiguoke.subscreen.util.ShizukuHelper;

public class SubScreenKeeper extends Service implements DisplayManager.DisplayListener {
    private static final String TAG = "SubScreenKeeper";
    private static final String CHANNEL_ID = "subscreen_keeper_channel";
    private static final int NOTIFICATION_ID = 1;

    private DisplayManager displayManager;
    private boolean subActivityLaunched = false;
    private ShizukuHelper shizukuHelper;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "SubScreenKeeper onCreate");

        // Must call startForeground() immediately for foreground service
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("副屏助手")
                .setContentText("正在监听副屏状态")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        shizukuHelper = new ShizukuHelper(this);

        // Register display listener - fires when outer screen activates on fold
        displayManager.registerDisplayListener(this, new Handler(Looper.getMainLooper()));
        Log.i(TAG, "DisplayListener registered");

        // Check immediately if there are already 2 displays
        checkAndLaunchSubScreen();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand");
        return START_STICKY;
    }

    /**
     * 检测副屏并尝试启动 SubActivity。
     *
     * 关键发现：Vivo X Flip 外屏 Display 1 的 canHostTasks=false，
     * 普通第三方 App 调用 setLaunchDisplayId() 会被静默拒绝。
     *
     * 解决方案（按优先级）：
     * 1. Shizuku 方案：通过 Shizuku 获取 shell 权限，执行 am start --display 1，
     *    shell 用户 (uid 2000) 可绕过 canHostTasks 限制（已验证可行）
     * 2. 直接启动方案：如果 App 已经在外屏上运行（用户手动从外屏打开），
     *    直接 startActivity 即可，不需要指定 Display
     * 3. 通知引导：如果以上都不可用，发通知引导用户从外屏手动打开 App
     */
    private void checkAndLaunchSubScreen() {
        Display[] displays = displayManager.getDisplays();
        Log.i(TAG, "checkAndLaunchSubScreen: displays.length=" + displays.length);

        if (displays.length >= 2 && !subActivityLaunched) {
            Display secondaryDisplay = null;
            for (Display display : displays) {
                if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                    secondaryDisplay = display;
                    break;
                }
            }

            if (secondaryDisplay != null) {
                int targetDisplayId = secondaryDisplay.getDisplayId();
                Log.i(TAG, "Secondary display found: displayId=" + targetDisplayId
                        + ", state=" + secondaryDisplay.getState());

                // 方案 1：尝试通过 Shizuku 以 shell 权限启动（绕过 canHostTasks=false）
                boolean launched = shizukuHelper.launchActivityOnDisplay(
                        SubActivity.class.getName(), targetDisplayId);

                if (launched) {
                    Log.i(TAG, "SubActivity launched on displayId=" + targetDisplayId
                            + " via Shizuku (shell privilege)");
                    subActivityLaunched = true;
                } else {
                    Log.w(TAG, "Shizuku not available. canHostTasks=false prevents"
                            + " setLaunchDisplayId() from app context.");
                    Log.w(TAG, "请安装并启动 Shizuku，或在 PC 上运行："
                            + "adb shell am start --display " + targetDisplayId
                            + " -n cn.pashiguoke.subscreen/.SubActivity");
                }
            }
        } else if (displays.length < 2) {
            subActivityLaunched = false;
            Log.i(TAG, "Only " + displays.length + " display(s), waiting for fold...");
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "副屏监听服务",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("保持服务运行以监听副屏状态变化");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    // --- DisplayManager.DisplayListener ---

    @Override
    public void onDisplayAdded(int displayId) {
        Log.i(TAG, "onDisplayAdded: displayId=" + displayId);
        // 外屏刚激活，延迟 500ms 等待 Display 状态稳定后再尝试启动
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            checkAndLaunchSubScreen();
        }, 500);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        Log.i(TAG, "onDisplayRemoved: displayId=" + displayId);
        subActivityLaunched = false;
    }

    @Override
    public void onDisplayChanged(int displayId) {
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        if (displayManager != null) {
            displayManager.unregisterDisplayListener(this);
        }
        super.onDestroy();
    }
}
