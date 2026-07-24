package cn.pashiguoke.subscreen.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import cn.pashiguoke.subscreen.service.SubScreenKeeper;

public class MYBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        switch (intent.getAction()){
            case Intent.ACTION_BOOT_COMPLETED:
                Log.i("SubScreenKeeper", "Boot completed, starting service");
                Intent serviceIntent = new Intent(context, SubScreenKeeper.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                break;
        }
    }
}
