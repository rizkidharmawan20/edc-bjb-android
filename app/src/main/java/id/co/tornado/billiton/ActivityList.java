package id.co.tornado.billiton;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.StrictMode;
import android.support.v7.app.AlertDialog;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;

import id.co.tornado.billiton.common.CommonConfig;
import id.co.tornado.billiton.common.NsiccsData;
import id.co.tornado.billiton.handler.JsonCompHandler;
import id.co.tornado.billiton.layout.FormMenu;
import id.co.tornado.billiton.layout.ListMenu;
import id.co.tornado.billiton.module.TapCard;
import me.grantland.widget.AutofitTextView;

public class ActivityList extends Activity {

    private LinearLayout linearLayout;
    private String id = "";
//    private AutofitTextView tv;
    public static final int RESULT_CLOSE_ALL = 0;
    private SharedPreferences preferences;
    private String compAct;
    private LinearLayout footer;
    private boolean pinpadServiceConnected = false;
    private boolean pinpadInUse = false;
    public TextView screenLog;

    private Messenger syncMessenger = null;
    private SocketService myServiceBinder;
    private Intent serviceIntent;

    public int modulStage = CommonConfig.ICC_PROCESS_STAGE_INIT;
    public NsiccsData cardData = new NsiccsData();

    public ServiceConnection myConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder binder) {
            myServiceBinder = ((SocketService.LocalBinder) binder).getServerInstance();
//            Log.d("ServiceConnection","connected");
//            showServiceData();
            doSetMenu();
        }

        public void onServiceDisconnected(ComponentName className) {
//            Log.d("ServiceConnection","disconnected");
            myServiceBinder = null;
        }
    };
    Handler flagHandler = new Handler() {
        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case CommonConfig.FLAG_INUSE:
                    pinpadInUse = true;
                    break;
                case CommonConfig.FLAG_READY:
                    pinpadInUse = false;
                    break;
                default:
                    super.handleMessage(message);
            }
      }
    };
    Messenger flagReceiver = new Messenger(flagHandler);

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i("PS", "Pinpad service connected");
            syncMessenger = new Messenger(service);
            pinpadServiceConnected = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i("PS", "Pinpad service disconnected");
            pinpadServiceConnected = false;
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bindService(new Intent(this, SocketService.class), myConnection, Context.BIND_AUTO_CREATE);
        serviceIntent = new Intent(this, InputPinService.class);
        startService(serviceIntent);
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
        setContentView(R.layout.activity_list);
//        tv = (AutofitTextView) findViewById(R.id.title_list);
        preferences = this.getSharedPreferences(CommonConfig.SETTINGS_FILE, this.getApplicationContext().MODE_PRIVATE);
        screenLog = (TextView) findViewById(R.id.screen_log);
        android.widget.TextView txTid = (android.widget.TextView) findViewById(R.id.textViewTID);
        android.widget.TextView txMid = (android.widget.TextView ) findViewById(R.id.textViewMID);
        android.widget.TextView txM2Name = (android.widget.TextView) findViewById(R.id.textView4);
        android.widget.TextView txMName = (android.widget.TextView ) findViewById(R.id.textViewMName);
        txTid.setText("TID : " + preferences.getString("terminal_id", CommonConfig.DEV_TERMINAL_ID));
        txMid.setText("MID : " + preferences.getString("merchant_id", CommonConfig.DEV_MERCHANT_ID));
        txMName.setText(preferences.getString("merchant_address1", CommonConfig.INIT_MERCHANT_ADDRESS1));
        txM2Name.setText(preferences.getString("merchant_name", CommonConfig.INIT_MERCHANT_NAME));
        linearLayout = (LinearLayout) findViewById(R.id.base_layout);
        footer = (LinearLayout) findViewById(R.id.base_print_footer);
//        Log.d("ACT", "Footer is " + footer.toString());
        compAct = getIntent().getExtras().getString("comp_act");
    }

    @Override
    protected void onResume() {
        super.onResume();
        preferences = this.getSharedPreferences(CommonConfig.SETTINGS_FILE, this.getApplicationContext().MODE_PRIVATE);
    }

    public void setMenu(JSONObject obj) {
        View child = null;
        Integer type = -1;
        try {
            type = obj.getInt("type");
            id = obj.get("id").toString();
//            tv.setText(obj.getString("title"));
        } catch (Exception e) {
            AlertDialog alertDialog = new AlertDialog.Builder(this).create();
            alertDialog.setTitle("Informasi");
            alertDialog.setMessage("EDC tidak terkoneksi dengan server\nGagal mengambil data\nSilahkan coba beberapa saat lagi.");
            alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK",
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
            alertDialog.show();
            e.printStackTrace();
            return;
        }
        tellService(false);

        if (type != -1 && !id.equals("")) {
            switch (type) {
                case CommonConfig.MenuType.Form:
                    bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
                    child = new FormMenu(this, id);
                    tellService(true);
                    break;
                case CommonConfig.MenuType.SecuredForm:
                    bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
                    child = new FormMenu(this, id);
                    break;
                case CommonConfig.MenuType.ListMenu:
                    child = new ListMenu(this, id);
                    break;
                case CommonConfig.MenuType.PopupBerhasil:
                    break;
                case CommonConfig.MenuType.PopupGagal:
                    break;
                case CommonConfig.MenuType.PopupLogout:
                    break;
            }

            linearLayout.removeAllViews();
            linearLayout.addView(child);
        }
    }

    public void setMenu(String id) {
        View child = null;
        Integer type = -1;
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        tellService(true);

        child = new FormMenu(this, id);
        linearLayout.removeAllViews();
        linearLayout.addView(child);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch(resultCode) {
            case RESULT_CLOSE_ALL:
                setResult(RESULT_CLOSE_ALL);
                finish();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        try {
            unbindService(myConnection);
            unbindService(serviceConnection);
        } catch (Exception e) {

        }
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        try {
            unbindService(myConnection);
            unbindService(serviceConnection);
        } catch (Exception e) {

        }
        super.onStop();
    }

    @Override
    public void onBackPressed() {
//       Log.d("BACK", "PRESSED");
       try {
           try {
               unbindService(myConnection);
               unbindService(serviceConnection);
           } catch (Exception e) {

           }
           super.onBackPressed();
       } catch (Exception e) {
           Log.e("BACK", "ERROR");
         //
       }
    }

    public void attachFooter(LinearLayout footerLayout) {
//        Log.d("ACT", "set footer");
//        Log.d("ACT", "Footer is " + footer.toString());
        int height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 212,
                getResources().getDisplayMetrics());
        linearLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height));
        footer = (LinearLayout) findViewById(R.id.base_print_footer);
//        footer.setVisibility(View.VISIBLE);
        footer.addView(footerLayout);
    }

    public void detachFooter() {
        Log.d("ACT", "unset footer");
        linearLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        footer = (LinearLayout) findViewById(R.id.base_print_footer);
        footer.removeAllViews();
//        footer.setVisibility(View.GONE);
    }

    private void tellService(boolean flag) {
        if (myServiceBinder!=null) {
            if (flag) {
                myServiceBinder.setIfForm();
//                Log.d("CHKIF", "Set to TRUE");
            } else {
                myServiceBinder.setIfNotForm();
//                Log.d("CHKIF", "Set to FALSE");
            }
        } else {
//            Log.d("CHKIF", "Binder na null");
        }
    }

    private void doSetMenu() {
        try {
            if(Arrays.asList(TapCard.BRIZZI_MENU).contains(compAct) && !compAct.equals(TapCard.TOPUP_ONLINE)) {
                setMenu(compAct);
            } else if (compAct.equals("0A0D0S")) {
                //prepare settlement
                JSONObject screen = prepareSettlement();
                setMenu(screen);
            }else{
//                setMenu(JsonCompHandler.readJson(this, compAct));
                setMenu(JsonCompHandler
                        .readJsonFromCacheIfAvailable(this, compAct)
//                        .readJsonFromUrl(compAct, this)
                );
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (JSONException e) {
            e.printStackTrace();
            //} catch (Exception e) {
            //    e.printStackTrace();
        }
    }

    public boolean isPinpadInUse() {
        return pinpadInUse;
    }

    public void setPinpadInUse(boolean pinpadInUse) {
        this.pinpadInUse = pinpadInUse;
    }

    public Messenger getSyncMessenger() {
        if (syncMessenger!=null) {
            Log.i("PARENT", "Get sync messenger");
        } else {
            Log.i("PARENT", "Get sync messenger return null");
        }
        return syncMessenger;
    }

    public boolean isWSConnected() {
        boolean connected = myServiceBinder.isConnect();
        if (!connected){
            myServiceBinder.forceReConnect();
        }
        return connected;
    }

    public void setSyncMessenger(Messenger syncMessenger) {
        this.syncMessenger = syncMessenger;
    }

    public boolean isPinpadServiceConnected() {
        return pinpadServiceConnected;
    }

    public JSONObject prepareSettlement() {
        JSONObject rootJson = new JSONObject();
        JSONObject screen = new JSONObject();
        try {
            screen.put("ver", "1");
            screen.put("id", "STL0001");
            screen.put("type", "1");
            screen.put("title", "Settlement");
            screen.put("static_menu", new JSONArray());
            screen.put("print", "");
            screen.put("print_text", "");
            screen.put("action_url", "S00001");
            JSONArray comp = new JSONArray();
            JSONObject data = new JSONObject();
            String lbl = "Proses";
            data.put("visible", "true");
            data.put("comp_lbl", lbl);
            data.put("comp_type", "7");
            data.put("comp_id", "G0003");
            data.put("seq", 0);
            JSONObject comps = new JSONObject();
            comp.put(data);
            comps.put("comp", comp);
            screen.put("comps", comps);
            rootJson.put("screen", screen);
        } catch (Exception e) {

        }
        return screen;
    }

}
