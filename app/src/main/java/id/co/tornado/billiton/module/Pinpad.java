package id.co.tornado.billiton.module;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;

import com.wizarpos.apidemo.util.StringUtility;
import com.wizarpos.jni.PinPadInterface;

import org.json.JSONObject;

/**
 * Created by indra on 05/01/16.
 */
public class Pinpad extends android.support.v7.widget.AppCompatEditText {

    static final int ERR_INPUT = -1;
    static final int RESULT_INPUT = 0;
    static boolean isFinish = true;
    private JSONObject comp;
    private static boolean bOpened = false;
    private static boolean bShowTextFlag = true;

    static final int ENCRYPT_TEXT_DIALOG = 0;
    static final int PINBLOCK_DIALOG = 1;
    static final int MAC_DIALOG = 2;


    private Handler handle = new Handler(){

        @Override
        public void handleMessage(Message msg) {
            String strInput = "123456789012345678";
            setText(strInput);
            if (msg.what == RESULT_INPUT){
                setText(msg.obj.toString());
            }
        }
    };
    public void closeDriveItem(){
        if(bOpened)
            PinPadInterface.close();
    }


    Runnable th = new Runnable(){
        public void run(){
            byte[] arryPinBlockBuffer = new byte[32];
            String strInput = getText().toString();
            if(strInput.equals("")|| strInput.length()!=18){
                strInput = "123456789012345678";
                handle.sendEmptyMessage(ERR_INPUT);
            }
//            int nResult = PinPadInterface.PinpadCalculatePinBlock(strInput.getBytes(), strInput.getBytes().length, arryPinBlockBuffer, -1, 0);
            int nResult = PinPadInterface.inputPIN(strInput.getBytes(), strInput.getBytes().length, arryPinBlockBuffer, -1, 0);
//            Log.d("PINPAD","nResult ="+nResult);
            if(nResult < 0)
                return;

            String strShow = StringUtility.ByteArrayToString(arryPinBlockBuffer, nResult);

            Message msg = new Message();
            msg.what =RESULT_INPUT ;
            msg.obj = strShow;
            handle.sendMessage(msg);
            isFinish = true;
        }
    };

    public Pinpad(Context context) {
        super(context);
    }

    public Pinpad(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public Pinpad(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }


    public void init() {
        int nResult = PinPadInterface.open();
//        Log.d("PINPAD", String.format("PinpadOpen() return value = %d\n", nResult));
        if(nResult >= 0)
        {
            bOpened = true;
            nResult = PinPadInterface.setKey(PinPadInterface.KEY_TYPE_MASTER, 0, 0, 1);
//            Log.d("PINPAD", String.format("PinpadSelectKey() return value = %d\n", nResult));
            //PinPadInterface.PinpadClose();
        }
        new Thread(th).start();
    }

    public boolean isbOpened() {
        return bOpened;
    }

}
