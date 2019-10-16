package id.co.tornado.billiton;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    public BootReceiver() {
    }

    @Override
    public void onReceive(Context context, Intent i) {
        if (i.getAction().equals(Intent.ACTION_BOOT_COMPLETED)){
            context.startService(new Intent(context,SocketService.class));
        }
    }
}
