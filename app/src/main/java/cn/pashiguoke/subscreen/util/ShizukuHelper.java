package cn.pashiguoke.subscreen.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;
// 注意：Vivo Android 16 过滤了所有 Log.d (debug) 级别日志，
// 本项目统一使用 Log.i (info) 级别确保日志可见。

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 辅助工具类。
 *
 * 核心原理：
 * Vivo X Flip 外屏 (Display 1) canHostTasks=false，普通第三方 App 调用
 * setLaunchDisplayId() 会被静默拒绝。但 ADB shell 用户 (uid 2000) 不受此限制。
 *
 * 本类通过 Shizuku.newProcess()（反射调用 private 方法）以 shell 权限执行
 * `am start --display <id>` 命令，绕过 canHostTasks=false 限制。
 *
 * 使用前提：
 * 1. 用户已安装 Shizuku App (https://shizuku.rikka.app/)
 * 2. Shizuku 服务已启动（通过无线调试或 USB 连接 PC）
 * 3. 本 App 已获得 Shizuku 授权
 */
public class ShizukuHelper {
    private static final String TAG = "ShizukuHelper";
    private static final String PACKAGE_NAME = "cn.pashiguoke.subscreen";
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    private final Context context;

    public ShizukuHelper(Context context) {
        this.context = context;
    }

    /**
     * 检查 Shizuku 是否已安装。
     */
    public boolean isShizukuInstalled() {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE,
                    PackageManager.GET_META_DATA);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            Log.i(TAG, "Shizuku app not installed");
            return false;
        }
    }

    /**
     * 检查 Shizuku 服务是否正在运行。
     */
    public boolean isShizukuRunning() {
        if (!isShizukuInstalled()) {
            return false;
        }
        try {
            boolean running = Shizuku.pingBinder();
            Log.i(TAG, "Shizuku.pingBinder() = " + running);
            return running;
        } catch (Exception e) {
            Log.i(TAG, "Shizuku service check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 检查 Shizuku 是否可用（已安装 + 服务运行 + 已授权）。
     */
    public boolean isShizukuAvailable() {
        if (!isShizukuRunning()) {
            return false;
        }
        try {
            int result = Shizuku.checkSelfPermission();
            Log.i(TAG, "Shizuku.checkSelfPermission() = " + result
                    + " (0 = granted)");
            return result == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            Log.i(TAG, "Shizuku permission check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 通过 Shizuku 以 shell 权限在指定 Display 上启动本应用的 Activity。
     *
     * 使用 `am start --display <id> -n <package>/<activity>` 命令。
     * Shell 用户 (uid 2000) 不受 canHostTasks=false 限制。
     *
     * @param activityClassName Activity 的完整类名（如 "cn.pashiguoke.subscreen.SubActivity"）
     * @param displayId 目标显示器 ID
     * @return true 如果启动成功
     */
    public boolean launchActivityOnDisplay(String activityClassName, int displayId) {
        Log.i(TAG, "Attempting to launch " + activityClassName + " on display " + displayId);

        if (!isShizukuAvailable()) {
            Log.i(TAG, "Shizuku not available, cannot launch on display " + displayId);
            return false;
        }

        // 构建组件名：cn.pashiguoke.subscreen/.SubActivity
        // --activity-clear-task 绕过 Vivo FlipToContinue 拦截
        String component = PACKAGE_NAME + "/" + activityClassName;
        String result = executeShellCommand("am", "start", "--display",
                String.valueOf(displayId), "--activity-clear-task",
                "-n", component);
        Log.i(TAG, "am start result: " + result);
        return result != null && result.contains("Starting:");
    }

    /**
     * 通过 Shizuku 以 shell 权限在指定 Display 上启动第三方应用。
     *
     * 核心策略（绕过 Vivo FlipToContinue 拦截）：
     *
     * Vivo X Flip 的 FlipToContinue 机制会拦截非白名单第三方 App 在外屏 (Display 1)
     * 的启动，显示"翻开手机以继续"。直接使用 `am start --display 1` 会被拦截。
     *
     * 本方法采用"主屏启动 + 移动 task + 杀掉拦截器"三步策略：
     * 1. 在主屏 (Display 0) 启动 App —— 不触发 FlipToContinue
     * 2. 等待 App 启动完成后，查找其 task ID
     * 3. 通过 `cmd activity display move-stack` 将 task 移动到外屏，
     *    并在同一 shell 命令中立即 `am force-stop com.vivo.fliplauncher`
     *    杀掉 FlipToContinue 拦截器。
     *
     * 关键原理：move-stack 成功后 task 短暂可见，FlipToContinue 需要约 1-2 秒
     * 才能启动拦截。在此窗口内 force-stop fliplauncher 进程即可阻止拦截。
     * fliplauncher 虽会自动重启，但不会重新拦截已显示的 App。
     *
     * @param packageName 第三方应用包名
     * @param displayId 目标显示器 ID（外屏通常为 1）
     * @return true 如果启动成功
     */
    public boolean launchPackageOnDisplay(String packageName, int displayId) {
        Log.i(TAG, "Attempting to launch " + packageName + " on display " + displayId);

        if (!isShizukuAvailable()) {
            Log.i(TAG, "Shizuku not available, cannot launch on display " + displayId);
            return false;
        }

        // 1. 解析启动入口 Activity
        String resolveResult = executeShellCommand("cmd", "package",
                "resolve-activity", "--brief", packageName);
        if (resolveResult == null || resolveResult.isEmpty()) {
            Log.e(TAG, "Failed to resolve launcher activity for " + packageName);
            return false;
        }

        String component = null;
        for (String line : resolveResult.split("\n")) {
            line = line.trim();
            if (line.contains("/")) {
                component = line;
                break;
            }
        }
        if (component == null) {
            Log.e(TAG, "Could not parse component from resolve output: " + resolveResult);
            return false;
        }
        Log.i(TAG, "Resolved launcher activity: " + component);

        // 如果目标就是主屏，直接启动即可
        if (displayId == 0) {
            String startResult = executeShellCommand("am", "start",
                    "--activity-clear-task", "-n", component);
            Log.i(TAG, "am start on display 0 result: " + startResult);
            return startResult != null && startResult.contains("Starting:");
        }

        // 2. Force-stop 清除残留进程
        executeShellCommand("am", "force-stop", packageName);
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // 3. 在主屏 (Display 0) 启动 App
        // 关键：必须使用 --display 0 显式指定主屏，否则在折叠状态下
        // （Display 0 OFF）am start 不会报错但进程不会启动。
        String startResult = executeShellCommand("am", "start",
                "--display", "0", "-n", component);
        Log.i(TAG, "am start on display 0 result: " + startResult);

        if (startResult == null || !startResult.contains("Starting:")) {
            Log.e(TAG, "Failed to start app on main display");
            return false;
        }

        // 4. 等待 App 启动完成
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 5. 查找 App 的 task ID
        // 通过 cmd activity stack list 获取 task 列表，
        // 查找包含目标包名的 task
        String stackList = executeShellCommand("cmd", "activity", "stack", "list");
        int taskId = -1;
        if (stackList != null) {
            for (String line : stackList.split("\n")) {
                line = line.trim();
                if (line.startsWith("taskId=") && line.contains(packageName)) {
                    // 格式: taskId=425: com.microsoft.todos/...
                    String idStr = line.substring(7).split(":")[0];
                    try {
                        taskId = Integer.parseInt(idStr.trim());
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                    break;
                }
            }
        }

        if (taskId == -1) {
            Log.e(TAG, "Could not find task ID for " + packageName
                    + ", stack list: " + stackList);
            return false;
        }
        Log.i(TAG, "Found task ID: " + taskId + " for " + packageName);

        // 6. 移动 task 到目标 Display + 立即 force-stop fliplauncher
        // 关键：两个命令在同一个 shell 中连续执行（用 ; 分隔），
        // 最小化 move-stack 和 force-stop 之间的延迟。
        // move-stack 成功后 FlipToContinue 需要约 1-2 秒才能启动，
        // 在此窗口内 force-stop 即可阻止拦截。
        // 即使 move-stack 失败（task 已在目标 display 上），
        // force-stop 仍会执行并清除已有的 FlipToContinue。
        String moveCmd = "cmd activity display move-stack " + taskId + " " + displayId
                + "; am force-stop com.vivo.fliplauncher";
        String moveResult = executeShellCommand("sh", "-c", moveCmd);
        Log.i(TAG, "move-stack + force-stop result: " + moveResult);

        // 7. 短暂等待确保状态稳定
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Log.i(TAG, "Launch sequence completed for " + packageName);
        return true;
    }

    /**
     * 通过 Shizuku 以 shell 权限查询已安装应用列表。
     *
     * Vivo Android 16 对普通 App 有包可见性限制，即使有 QUERY_ALL_PACKAGES 权限，
     * getInstalledApplications() 也只返回自身。
     *
     * 通过 Shizuku.newProcess() 执行 `pm list packages -3`，
     * 以 shell 权限获取第三方包名列表。
     *
     * @return 包名列表，如果 Shizuku 不可用则返回 null
     */
    public List<String> getInstalledPackageNamesViaShizuku() {
        if (!isShizukuAvailable()) {
            Log.i(TAG, "Shizuku not available, cannot query packages");
            return null;
        }

        String output = executeShellCommand("pm", "list", "packages", "-3");
        if (output == null) {
            return null;
        }

        List<String> packages = new ArrayList<>();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) {
                String pkg = line.substring(8).trim();
                if (!pkg.isEmpty()) {
                    packages.add(pkg);
                }
            }
        }

        Log.i(TAG, "getInstalledPackageNamesViaShizuku: returned "
                + packages.size() + " packages");
        return packages;
    }

    /**
     * 通过反射调用 Shizuku.newProcess() 执行 shell 命令。
     *
     * Shizuku.newProcess() 在 v13.1.5 中是 private 方法，
     * 通过 getDeclaredMethod + setAccessible(true) 调用。
     *
     * @param cmd 命令和参数数组（如 {"am", "start", "--display", "1"}）
     * @return 命令输出字符串，失败返回 null
     */
    private String executeShellCommand(String... cmd) {
        try {
            Method newProcessMethod = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            newProcessMethod.setAccessible(true);

            Process process = (Process) newProcessMethod.invoke(null,
                    cmd, null, null);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            // 也读取 stderr
            BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()));
            while ((line = errReader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            process.waitFor();
            return sb.toString().trim();

        } catch (Exception e) {
            Log.e(TAG, "Failed to execute shell command: " + e.getMessage(), e);
            return null;
        }
    }
}
