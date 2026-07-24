package cn.pashiguoke.subscreen;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;

import cn.pashiguoke.subscreen.service.SubScreenKeeper;

public class MainActivity extends AppCompatActivity {
    View personal_bg;
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        //透明状态栏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        //透明导航栏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        personal_bg = findViewById(R.id.mainBack);

        initBackground();
        RequestPermissions();

        // Check if we're already on the outer screen (e.g. Vivo X Flip folded)
        // canHostTasks=false prevents setLaunchDisplayId(), but we can launch directly
        int currentDisplayId = getWindowManager().getDefaultDisplay().getDisplayId();
        Log.i("MainActivity", "currentDisplayId=" + currentDisplayId);
        if (currentDisplayId != Display.DEFAULT_DISPLAY) {
            Log.i("MainActivity", "On outer screen, launching SubActivity directly");
            Intent subIntent = new Intent(this, SubActivity.class);
            subIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(subIntent);
            finish();
            return;
        }

        // 显示器管理的
        DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display[] displays = displayManager.getDisplays();
        ((TextView)findViewById(R.id.infText)).setText("不出意外副屏已经开起来了");
        if(displays.length<2){
            ((TextView)findViewById(R.id.infText)).setText("未检测到副屏");
        }

        // 启动前台 Service（Android 8+ 必须用 startForegroundService）
        Intent serviceIntent = new Intent(this, SubScreenKeeper.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // 提示 Shizuku 状态
        cn.pashiguoke.subscreen.util.ShizukuHelper shizukuHelper =
                new cn.pashiguoke.subscreen.util.ShizukuHelper(this);
        if (shizukuHelper.isShizukuAvailable()) {
            ((TextView)findViewById(R.id.infText)).setText("Shizuku 已就绪，折叠后将自动启动副屏");
        } else if (shizukuHelper.isShizukuInstalled()) {
            ((TextView)findViewById(R.id.infText)).setText("Shizuku 已安装但未启动，请启动 Shizuku 服务");
        } else {
            ((TextView)findViewById(R.id.infText)).setText("未安装 Shizuku，折叠后需手动启动副屏");
        }

    }
    // 申请权限
    @RequiresApi(api = Build.VERSION_CODES.M)
    private void RequestPermissions(){
        String[] permissions = new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.INTERNET
        };
        for (String permission:permissions) {
            while (checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED){
                Log.i("TAG", "RequestPermissions: "+permission);
                requestPermissions(new String[]{permission},0);
            }

        }
    }
    // 初始化设置背景
    private void initBackground(){

        // 换背景的
        if(new File(getCacheDir()+"/background").exists()){
            personal_bg.setBackground(Drawable.createFromPath(getCacheDir()+"/background"));
        }else{
            personal_bg.setBackgroundResource(R.drawable.background);
        }
        new DownloadImageTask().execute("https://picsum.photos/720/1280");
        if(!new File(getCacheDir()+"/time_v2.html").exists()){
            try {
                FileWriter fw = new FileWriter(getCacheDir()+"/time_v2.html");
                fw.write("<!DOCTYPE html>\n" +
                        "<html lang=\"en\">\n" +
                        "<head>\n" +
                        "    <meta charset=\"UTF-8\">\n" +
                        "    <meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">\n" +
                        "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                        "    <title>时间</title>\n" +
                        "    <style>\n" +
                        "        *{\n" +
                        "            margin: 0px;\n" +
                        "            padding: 0px;\n" +
                        "            --color-number: #fff;\n" +
                        "        }\n" +
                        "        body{\n" +
                        "            width: 100vw;\n" +
                        "            height: 100vh;\n" +
                        "            overflow: hidden;\n" +
                        "            background: #000;\n" +
                        "        }\n" +
                        "        .show{\n" +
                        "            width: 100vw;\n" +
                        "            height: 100vh;\n" +
                        "            display: flex;\n" +
                        "            flex-direction: column;\n" +
                        "            justify-content: center;\n" +
                        "            align-items: center;\n" +
                        "        }\n" +
                        "        .time{\n" +
                        "            font-size: 18vw;\n" +
                        "            font-weight: 700;\n" +
                        "            text-shadow: 0px 0px 10vw rgba(255,255,255,0.3);\n" +
                        "            color: #fff;\n" +
                        "            letter-spacing: -1vw;\n" +
                        "        }\n" +
                        "        .date{\n" +
                        "            font-size: 6vw;\n" +
                        "            color: #aaa;\n" +
                        "            margin-top: 2vh;\n" +
                        "        }\n" +
                        "    </style>\n" +
                        "</head>\n" +
                        "<body>\n" +
                        "    <div class=\"show\">\n" +
                        "        <div class=\"time\">00:00</div>\n" +
                        "        <div class=\"date\">--</div>\n" +
                        "    </div>\n" +
                        "    <script>\n" +
                        "        function updateTime(){\n" +
                        "            var d = new Date();\n" +
                        "            var h = String(d.getHours()).padStart(2,'0');\n" +
                        "            var m = String(d.getMinutes()).padStart(2,'0');\n" +
                        "            document.querySelector(\".time\").innerText = h+':'+m;\n" +
                        "            var month = String(d.getMonth()+1).padStart(2,'0');\n" +
                        "            var day = String(d.getDate()).padStart(2,'0');\n" +
                        "            var week = ['日','一','二','三','四','五','六'][d.getDay()];\n" +
                        "            document.querySelector(\".date\").innerText = month+'-'+day+' 周'+week;\n" +
                        "        }\n" +
                        "        updateTime();\n" +
                        "        setInterval(updateTime,1000);\n" +
                        "    </script>\n" +
                        "</body>\n" +
                        "</html>");
                fw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }
    private Drawable loadImageFromNetwork(String imageUrl) {
        Drawable drawable = null;
        try {
            drawable = Drawable.createFromStream(new URL(imageUrl).openStream(), null);

        } catch (Exception e) {
            Log.i("MainActivity", e.getMessage());
        }
        if (drawable == null) {
            Log.i("MainActivity", "null drawable");
        } else {
            Log.i("MainActivity", "not null drawable");
        }

        return drawable;
    }

    private class DownloadImageTask extends AsyncTask<String, Void, Drawable> {

        protected Drawable doInBackground(String... urls) {
            return loadImageFromNetwork(urls[0]);
        }

        @SuppressLint("WrongThread")
        protected void onPostExecute(Drawable result) {
            try {
                // 缓存到本地下次打开使用
                File pic = new File(getCacheDir() + "/background");
                if (pic.exists()) {
                    pic.delete();
                }else{
                    //personal_bg.setBackground(result);
                }

                FileOutputStream fo = new FileOutputStream(pic);
                ((BitmapDrawable) result).getBitmap().compress(Bitmap.CompressFormat.PNG, 100, fo);
                fo.close();

            }catch (Exception e){}

        }
    }
}