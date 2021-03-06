/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package id.co.tornado.billiton.handler;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.wizarpos.apidemo.printer.FontSize;
import com.wizarpos.apidemo.printer.PrintSize;

//import com.wizarpos.apidemo.smartcard.SmartCardController;
//import com.wizarpos.jni.PinPadInterface;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import id.co.tornado.billiton.common.CommonConfig;
import id.co.tornado.billiton.common.StringLib;
import id.co.tornado.billiton.module.listener.ChannelClient;

/**
 * @author Ahmad
 */
public class txHandler {

    static int POSUSER = 1;
    static int ACTIVEUSER = 1;
    static int BLOCKEDUSER = 3;
    static int PASSWORDRETRY = 3;
    static int IN_USE = 1;
    static int IDLE = 0;
    static byte[] busyResponse = ISO8583Parser.hexStringToByteArray("FF");
    String serviceid;
    private Cursor c;
    private Context ctx;
    private DataBaseHelper helperDb;
    private LogHandler EDCLog;
    private JSONObject jroot;
    private Date date = new Date();
    private int msgSequence = 0;
    private ChannelClient cNio = null;
    private String stanvoid = "";
    String hsToHost;
    int elogid;
    int socket_status;
    boolean predefined_stan = false;
    boolean DEBUG_LOG = true;
    private List<PrintSize> printData;
    private boolean hasPrintData = false;
    private String printText = "";

    private static txHandler instance;

    private txHandler() {
        socket_status = IDLE;
    }

    public static txHandler getInstance() {
        if (instance==null) {
            instance = new txHandler();
        }
        return instance;
    }

    private static void logTrace(String pMessage) {
        Log.i("SOCKET", pMessage);
    }

    public void setContext(Context context) {
        this.ctx = context;
    }

    //Main handler for service request (transaction API)
    public JSONObject processTransaction(Context context, String content) throws JSONException, Exception, IOException {
        //prepare return object
        helperDb = new DataBaseHelper(context);
        EDCLog = new LogHandler(context);
        SQLiteDatabase clientDB = null;
        jroot = new JSONObject();
        setHasPrintData(false);
        JSONObject jmsg = new JSONObject();
        jmsg.put("msg_rc", "05");
        JSONObject jrequest = new JSONObject();
        JSONObject rqContent = new JSONObject();
        //parsing input request
        try {
            helperDb.openDataBase();
            clientDB = helperDb.getActiveDatabase();
            jrequest = new JSONObject(content);
        } catch (Exception ex) {
            Log.i("TX", "DB error");
        }
        //get request from msg
        rqContent = (JSONObject) jrequest.get("msg");

        //use local function to parse transaction header
        String[] txElements = getTransactionElements(rqContent);
        jmsg.put("msg_id", txElements[0]);
        jmsg.put("msg_ui", txElements[1]);
        jmsg.put("msg_si", txElements[2]);
        boolean isLogon = txElements[2].equals("L00001");
        boolean isInitBrizzi = txElements[2].equals("A21100");
        boolean isBrizziSettlement = txElements[2].equals("A28100");
        boolean isBrizziVoid = txElements[2].equals("A2C100");
        boolean isTunaiVoid = txElements[2].equals("A64000");
        serviceid = txElements[2];
        predefined_stan = false;
        if (rqContent.has("msg_stan")) {
            msgSequence = Integer.parseInt(rqContent.getString("msg_stan"));
            writeDebugLog("TAP2SERV", "Forced stan received : " + rqContent.getString("msg_stan"));
            writeDebugLog("TAP2SERV", "Int : " + String.valueOf(msgSequence));
            //Calibrating sequence
            if (!isBrizziVoid) {
                msgSequence--;
            }
            predefined_stan = true;
        }
        writeDebugLog("STAN : ", msgSequence+"");

        if (isBrizziSettlement) {
            writeDebugLog("EDCLOG", "read (163)");
            String qry = "select * from edc_log where service_id like 'A24%' " +
                    "and (lower(settled) <> 't' or settled is null) and rc = '00' " +
                    "and (lower(reversed) <> 't' or reversed is null)";
            Cursor sData = clientDB.rawQuery(qry, null);
            StringBuilder idlist = new StringBuilder();
            idlist.append(" (");
            double totalSettlement = 0;
            if (sData.moveToFirst()) {
                boolean onProgress = false;
                int i = 0;
                int j = 0;
                StringBuilder stringBuilder = new StringBuilder();
                do {
                    j = 0;
                    do {
                        String tms = sData.getString(sData.getColumnIndex("rqtime"));
                        String date = "000000";
                        String time = "000000";
                        if (tms.length() == 19) {
                            date = tms.substring(2, 4) + tms.substring(5, 7) + tms.substring(8, 10);
                            time = tms.substring(11, 13) + tms.substring(14, 16) + tms.substring(17);
                        }
                        stringBuilder.append(date);
                        stringBuilder.append(time);
                        String proccode = sData.getString(sData.getColumnIndex("proccode"));
                        stringBuilder.append(proccode);
                        double amt = sData.getDouble(sData.getColumnIndex("amount"));
                        totalSettlement += (amt/100);
                        stringBuilder.append(StringLib.fillZero(String.valueOf((int) amt), 10));
                        String stan = sData.getString(sData.getColumnIndex("stan"));
                        stringBuilder.append(stan);
                        String batchno = sData.getString(sData.getColumnIndex("batchno"));
                        stringBuilder.append(batchno.substring(batchno.length() - 2));
                        String cardno = sData.getString(sData.getColumnIndex("track2"));
                        stringBuilder.append(cardno);
                        String hash = sData.getString(sData.getColumnIndex("hash"));
                        stringBuilder.append(hash);
                        idlist.append(String.valueOf(sData.getInt(sData.getColumnIndex("log_id"))));
                        if (!sData.isLast()) {
                            idlist.append(",");
                        }
                        j++;
                    } while (j<15&&sData.moveToNext());
                    i++;
                    String iServiceData = "insert or replace into service_data (message_id, name, value) "
                            + "values ('" + jmsg.get("msg_id") + "', 'rowdata', '"
                            + stringBuilder.toString() + "')";
                    clientDB.execSQL(iServiceData);
                    iServiceData = "insert or replace into service_data (message_id, name, value) "
                            + "values ('" + jmsg.get("msg_id") + "', 'pcode', '"
                            + "808200" + "')";
                    clientDB.execSQL(iServiceData);
                    if (msgSequence==0&&!predefined_stan) {
                        String getStanSeq = "select seq msgSequence from holder";
                        Cursor stanSeq = clientDB.rawQuery(getStanSeq, null);
                        if (stanSeq != null) {
                            stanSeq.moveToFirst();
                            msgSequence = stanSeq.getInt(0);
                        }
                        stanSeq.close();
                    }
                    String trace_no = generateStan();
                    String toParse = jmsg.get("msg_id") + "|" + txElements[3] + "|" + txElements[4];
                    ISO8583Parser rqParser = new ISO8583Parser(context, "6000070000", serviceid, toParse, 1, trace_no);
                    String uStanSeq = "update holder set "
                            + "seq = " + msgSequence;
                    writeDebugLog("UPDATING", "HOLDER (229)");
                    clientDB.execSQL(uStanSeq);
                    onProgress = true;
                    byte[] toHost = rqParser.parseJSON();
                    int cLen = toHost.length;
                    byte[] hLen = Arrays.copyOfRange(ByteBuffer.allocate(4).putInt(cLen).array(), 2, 4);
                    byte[] formattedContent = ByteBuffer.allocate(2 + cLen).put(hLen).put(toHost).array();
                    hsToHost = ISO8583Parser.bytesToHex(formattedContent);
                    SharedPreferences preferences = ctx.getSharedPreferences(CommonConfig.SETTINGS_FILE, Context.MODE_PRIVATE);
                    String host_ip = preferences.getString("ip", CommonConfig.DEV_SOCKET_IP);
                    int host_port = Integer.valueOf(preferences.getString("port", CommonConfig.DEV_SOCKET_PORT));
                    AsyncMessageWrapper amw = new AsyncMessageWrapper(host_ip, host_port, hsToHost);
                    byte[] fromHost = sendMessage(amw, 30000);
                    while (onProgress) {
                        Thread.sleep(1000);
                        if (socket_status==IDLE) {
                            onProgress = false;
                        }
                    }
                    if (fromHost==null) {
                        if (clientDB.isOpen()) {
                            clientDB.close();
                        }
                        return new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":[{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"Tidak mendapat response settlement\",\n" +
                                "\"value\":\"Tidak mendapat response settlement\"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"P00001\",\"seq\":0}]},\"id\":\"000000F\",\n" +
                                "\"type\":\"3\",\"title\":\"Settlement\"}}");
                    }
                } while (sData.moveToNext());
                String baris = String.valueOf(((i-1)*15)+j);
                idlist.append(") ");
                int ts = (int) totalSettlement;
                String stringTs = StringLib.strToCurr(String.valueOf(ts), "Rp");
                //fix dont display all brizzi report after settlement
//                String updDB = "update edc_log set settled = 't' where log_id in " + idlist.toString() + ";";
                writeDebugLog("EDCLOG", "update settled (264)");
                String updDB = "update edc_log set settled = 't' where service_id like 'A2%';";
                clientDB.execSQL(updDB);
                String updBatch = "update holder set batch = case batch when 99 then 0 else batch + 1 end";
                writeDebugLog("UPDATING", "HOLDER BATCH (266)");
                clientDB.execSQL(updBatch);
                clientDB.close();
//start mod settlement printout
                String cmp = "";
                int sq = 0;
                String ssq = String.valueOf(sq);
                cmp += "{\"visible\":true,\"comp_values\":"
                        + "{\"comp_value\":["
                        + "{\"print\":\"----------------------------------------\",\n"
                        + "\"value\":\"----------------------------------------\"}"
                        + "]},\"comp_lbl\":\"\",\"comp_type\":\"1\","
                        + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"}";
                sq++;
                ssq = String.valueOf(sq);
                cmp += ",";
                cmp += "{\"visible\":true,\"comp_values\":"
                        + "{\"comp_value\":["
                        + "{\"print\":\"TRANSAKSI           COUNT         AMOUNT\",\n"
                        + "\"value\":\"TRANSAKSI           COUNT         AMOUNT\"}"
                        + "]},\"comp_lbl\":\"\",\"comp_type\":\"1\","
                        + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"}";
                sq++;
                ssq = String.valueOf(sq);
                cmp += ",";
                cmp += "{\"visible\":true,\"comp_values\":"
                        + "{\"comp_value\":["
                        + "{\"print\":\"----------------------------------------\",\n"
                        + "\"value\":\"----------------------------------------\"}"
                        + "]},\"comp_lbl\":\"\",\"comp_type\":\"1\","
                        + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"}";
                sq++;
                ssq = String.valueOf(sq);
                cmp += ",";
                String clab = "PEMBAYARAN";
                cmp += "{\"visible\":true,\"comp_values\":"
                        + "{\"comp_value\":["
                        + "{\"print\":\"" + stringTs + "\",\n"
                        + "\"value\":\"" + stringTs + "\"}"
                        + "]},\"comp_lbl\":\"" + clab + " :"+ baris +"\",\"comp_type\":\"1\","
                        + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"}";
                sq++;
                ssq = String.valueOf(sq);
                cmp += ",";
                cmp += "{\"visible\":true,\"comp_values\":"
                        + "{\"comp_value\":["
                        + "{\"print\":\"----------------------------------------\",\n"
                        + "\"value\":\"----------------------------------------\"}"
                        + "]},\"comp_lbl\":\"\",\"comp_type\":\"1\","
                        + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"}";
                sq++;
                ssq = String.valueOf(sq);
                cmp += ",";
                cmp += "{\"visible\":true,\"comp_values\":"
                        + "{\"comp_value\":["
                        + "{\"print\":\"[B]"+ stringTs +"\",\n"
                        + "\"value\":\"[B]"+ stringTs +"\"}"
                        + "]},\"comp_lbl\":\"[B]TOTAL :" + baris + "\",\"comp_type\":\"1\","
                        + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"}";
                sq++;
                ssq = String.valueOf(sq);
                cmp += ",";
                cmp += "{\"visible\":true,\"comp_values\":"
                        + "{\"comp_value\":["
                        + "{\"print\":\"----------------------------------------\",\n"
                        + "\"value\":\"----------------------------------------\"}"
                        + "]},\"comp_lbl\":\"\",\"comp_type\":\"1\","
                        + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"}";
                return new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":["
                        + cmp
                        + "]},\"id\":\"281000F\",\n" +
                        "\"type\":\"1\",\"title\":\"Settlement BRIZZI\",\"print\":\"2\",\"print_text\":\"STL\"}}");
//end mod settlement printout
//                return new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":[" +
//                        "{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\""+baris+" transaksi\"," +
//                        "\"value\":\""+baris+" transaksi\"}]},\"comp_lbl\":\"Jumlah : \",\"comp_type\":\"1\",\"comp_id\":\"281001\",\"seq\":0}," +
//                        "{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\""+stringTs+"\"," +
//                        "\"value\":\""+stringTs+"\"}]},\"comp_lbl\":\"Total  : \",\"comp_type\":\"1\",\"comp_id\":\"281002\",\"seq\":1}," +
//                        "{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\" \"," +
//                        "\"value\":\" \"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"281003\",\"seq\":2}" +
//                        "]},\"id\":\"281000F\",\"type\":\"1\",\"title\":\"Settlement BRIZZI Report\",\"print\":\"2\",\"print_text\":\"STL\"}}");
            } else {
                if (clientDB.isOpen()) {

                    clientDB.close();
                }
                return new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":[{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"Tidak terdapat data settlement\",\n" +
                        "\"value\":\"Tidak terdapat data settlement\"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"P00001\",\"seq\":0}]},\"id\":\"000000F\",\n" +
                        "\"type\":\"3\",\"title\":\"Settlement\"}}");
            }
        }
        if (isBrizziVoid) {
            writeDebugLog("VOID", (String) rqContent.get("msg_dt"));
            String trace_number = (String) rqContent.get("msg_dt");
            writeDebugLog("EDCLOG", "read (362)");
            String qry = "select * from edc_log where service_id like 'A24%' " +
                    "and (lower(settled) <> 't' or settled is null) and rc = '00' " +
                    "and (lower(reversed) <> 't' or reversed is null) " +
                    "and cast(stan as integer) = " + trace_number + " order by rqtime desc";
            Cursor sData = clientDB.rawQuery(qry, null);
            StringBuilder stringBuilder = new StringBuilder();
            boolean onProgress = false;
            if (sData.moveToFirst()) {
                stanvoid = String.valueOf(sData.getInt(sData.getColumnIndex("log_id")));
                String tms = sData.getString(sData.getColumnIndex("rqtime"));
                String date = "000000";
                String time = "000000";
                if (tms.length() == 19) {
                    date = tms.substring(2, 4) + tms.substring(5, 7) + tms.substring(8, 10);
                    time = tms.substring(11, 13) + tms.substring(14, 16) + tms.substring(17);
                }
                stringBuilder.append(date);
                stringBuilder.append(time);
                String proccode = sData.getString(sData.getColumnIndex("proccode"));
                stringBuilder.append(proccode);
                double amt = sData.getDouble(sData.getColumnIndex("amount"));
                stringBuilder.append(StringLib.fillZero(String.valueOf((int) amt), 10));
                String stan = sData.getString(sData.getColumnIndex("stan"));
                stringBuilder.append(stan);
                String batchno = sData.getString(sData.getColumnIndex("batchno"));
                stringBuilder.append(batchno.substring(batchno.length() - 2));
                String cardno = sData.getString(sData.getColumnIndex("track2"));
                stringBuilder.append(cardno);
                String hash = sData.getString(sData.getColumnIndex("hash"));
                stringBuilder.append(hash);
                String iServiceData = "insert or replace into service_data (message_id, name, value) "
                        + "values ('" + jmsg.get("msg_id") + "', 'rowdata', '"
                        + stringBuilder.toString() + "')";
                clientDB.execSQL(iServiceData);
                iServiceData = "insert or replace into service_data (message_id, name, value) "
                        + "values ('" + jmsg.get("msg_id") + "', 'pcode', '"
                        + "808201" + "')";
                clientDB.execSQL(iServiceData);
                if (!predefined_stan) {
                    String getStanSeq = "select seq msgSequence from holder";
                    Cursor stanSeq = clientDB.rawQuery(getStanSeq, null);
                    if (stanSeq != null) {
                        stanSeq.moveToFirst();
                        msgSequence = stanSeq.getInt(0);
                    }
                    stanSeq.close();
                }
                String trace_no = stan;
                String toParse = jmsg.get("msg_id") + "|" + txElements[3] + "|" + txElements[4];
                ISO8583Parser rqParser = new ISO8583Parser(context, "6000070000", serviceid, toParse, 1, trace_no);
                onProgress = true;
                byte[] toHost = rqParser.parseJSON();
                int cLen = toHost.length;
                byte[] hLen = Arrays.copyOfRange(ByteBuffer.allocate(4).putInt(cLen).array(), 2, 4);
                byte[] formattedContent = ByteBuffer.allocate(2 + cLen).put(hLen).put(toHost).array();
                hsToHost = ISO8583Parser.bytesToHex(formattedContent);
                SharedPreferences preferences = ctx.getSharedPreferences(CommonConfig.SETTINGS_FILE, Context.MODE_PRIVATE);
                String host_ip = preferences.getString("ip", CommonConfig.DEV_SOCKET_IP);
                int host_port = Integer.valueOf(preferences.getString("port", CommonConfig.DEV_SOCKET_PORT));
                AsyncMessageWrapper amw = new AsyncMessageWrapper(host_ip, host_port, hsToHost);
                byte[] fromHost = sendMessage(amw, 30000);
                while (onProgress) {
                    Thread.sleep(1000);
                    if (socket_status == IDLE) {
                        onProgress = false;
                    }
                }
                if (fromHost == null) {
                    if (clientDB.isOpen()) {
                        clientDB.close();
                    }
                    return new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":[{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"Tidak mendapat response void\",\n" +
                            "\"value\":\"Tidak mendapat response void\"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"P00001\",\"seq\":0}]},\"id\":\"000000F\",\n" +
                            "\"type\":\"3\",\"title\":\"Void BRIZZI\"}}");
                } else {
                    ISO8583Parser rpParser = new ISO8583Parser(context, "6000070000", ISO8583Parser.bytesToHex(fromHost), 2);
                    String[] replyValues = rpParser.getIsoBitValue();
                    String serverRef = replyValues[37];
                    String serverDate = replyValues[13];
                    String serverTime = replyValues[12];
                    if (clientDB.isOpen()) {
                        clientDB.close();
                    }
                    return new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":[" +
                            "{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"void telah terkirim\",\n" +
                            "\"value\":\"void telah terkirim\"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"P00001\",\"seq\":0}," +
                            "{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"" + stanvoid + "\",\n" +
                            "\"value\":\"" + stanvoid + "\"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"P00002\",\"seq\":1}" +
                            "]},\"id\":\"2C10000\",\n" +
                            "\"type\":\"1\",\"action_url\":\"A2C100\",\"title\":\"Void BRIZZI\",\"server_date\":\"" + serverDate + "\"" +
                            ",\"server_time\":\"" + serverTime + "\",\"server_ref\":\"" + serverRef + "\"}}");
                }
            } else {
                if (clientDB.isOpen()) {
                    clientDB.close();
                }
                return new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":[{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"Tidak terdapat data transaksi\",\n" +
                        "\"value\":\"Tidak terdapat data transaksi\"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"P00001\",\"seq\":0}]},\"id\":\"000000F\",\n" +
                        "\"type\":\"3\",\"title\":\"Void BRIZZI\"}}");
            }
        }
        if (isTunaiVoid) {
            String trace_number = (String) rqContent.get("msg_dt");
            writeDebugLog("MSGLOG", "read (466)");
            writeDebugLog("EDCLOG", "read (467)");
            String qry = "select a.track2, a.log_id, b.response_message, a.amount, a.stan, b.log_id mlog from edc_log a, messagelog b where " +
                    "a.stan = b.message_id and date(a.rqtime)=date(b.request_time) and a.service_id=b.service_id " +
                    "and a.service_id = 'A63001' " +
                    "and (lower(a.settled) <> 't' or a.settled is null) and rc = '00' " +
                    "and (lower(a.reversed) <> 't' or a.reversed is null) " +
                    "and cast(a.stan as integer) = " + trace_number + " order by a.rqtime desc";
            Cursor sData = clientDB.rawQuery(qry, null);
            StringBuilder stringBuilder = new StringBuilder();
            boolean onProgress = false;
            if (sData.moveToFirst()) {
                // do void tarik tunai here (reversal)
                stanvoid = String.valueOf(sData.getInt(sData.getColumnIndex("log_id")));
                String mlogid = String.valueOf(sData.getInt(sData.getColumnIndex("mlog")));
                String ttResp = sData.getString(sData.getColumnIndex("response_message"));
                JSONObject ttScreen = new JSONObject(ttResp);
                double amt = sData.getDouble(sData.getColumnIndex("amount"))/100;
                String ttAmount = String.valueOf((int) amt);
                String iServiceData = "insert or replace into service_data (message_id, name, value) "
                        + "values ('" + jmsg.get("msg_id") + "', 'sal_amount', '"
                        + ttAmount + "')";
                clientDB.execSQL(iServiceData);
                String ttDate = (String) ttScreen.get("server_date");
                iServiceData = "insert or replace into service_data (message_id, name, value) "
                        + "values ('" + jmsg.get("msg_id") + "', 'old_date', '"
                        + ttDate + "')";
                clientDB.execSQL(iServiceData);
                String ttTime = (String) ttScreen.get("server_time");
                iServiceData = "insert or replace into service_data (message_id, name, value) "
                        + "values ('" + jmsg.get("msg_id") + "', 'old_time', '"
                        + ttTime + "')";
                clientDB.execSQL(iServiceData);
                String ttRef = sData.getString(sData.getColumnIndex("stan"));
                iServiceData = "insert or replace into service_data (message_id, name, value) "
                        + "values ('" + jmsg.get("msg_id") + "', 'old_ref', '"
                        + ttRef + "')";
                clientDB.execSQL(iServiceData);
                String ttAir = (String) ttScreen.get("server_air");
                iServiceData = "insert or replace into service_data (message_id, name, value) "
                        + "values ('" + jmsg.get("msg_id") + "', 'old_air', '"
                        + ttAir + "')";
                clientDB.execSQL(iServiceData);
                String ttTrack2 = sData.getString(sData.getColumnIndex("track2"));
                String cardNo = ttTrack2.substring(0, ttTrack2.indexOf("=")-1);
                if (msgSequence == 0&&!predefined_stan) {
                    String getStanSeq = "select seq msgSequence from holder";
                    Cursor stanSeq = clientDB.rawQuery(getStanSeq, null);
                    if (stanSeq != null) {
                        stanSeq.moveToFirst();
                        msgSequence = stanSeq.getInt(0);
                    }
                    stanSeq.close();
                }
//                String trace_no = generateStan();
                String toParse = jmsg.get("msg_id") + "|" + txElements[3] + "|" + txElements[4];
                ISO8583Parser rqParser = new ISO8583Parser(context, "6000070000", serviceid, toParse, 1, trace_number);
                String uStanSeq = "update holder set "
                        + "seq = " + msgSequence;
                writeDebugLog("UPDATING", "HOLDER (519)");
                clientDB.execSQL(uStanSeq);
                onProgress = true;
                byte[] toHost = rqParser.parseJSON();
                int cLen = toHost.length;
                byte[] hLen = Arrays.copyOfRange(ByteBuffer.allocate(4).putInt(cLen).array(), 2, 4);
                byte[] formattedContent = ByteBuffer.allocate(2 + cLen).put(hLen).put(toHost).array();
                hsToHost = ISO8583Parser.bytesToHex(formattedContent);
                SharedPreferences preferences = ctx.getSharedPreferences(CommonConfig.SETTINGS_FILE, Context.MODE_PRIVATE);
                String host_ip = preferences.getString("ip", CommonConfig.DEV_SOCKET_IP);
                int host_port = Integer.valueOf(preferences.getString("port", CommonConfig.DEV_SOCKET_PORT));
                AsyncMessageWrapper amw = new AsyncMessageWrapper(host_ip, host_port, hsToHost);
                byte[] fromHost = sendMessage(amw, 30000);
                while (onProgress) {
                    Thread.sleep(1000);
                    if (socket_status == IDLE) {
                        onProgress = false;
                    }
                }
                if (fromHost == null) {
                    if (clientDB.isOpen()) {
                        clientDB.close();
                    }
                    return new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":[{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"Tidak mendapat response void\",\n" +
                            "\"value\":\"Tidak mendapat response void\"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"P00001\",\"seq\":0}]},\"id\":\"000000F\",\n" +
                            "\"type\":\"3\",\"title\":\"Void Tarik Tunai\"}}");
                } else {
                    ISO8583Parser rpParser = new ISO8583Parser(context, "6000070000", ISO8583Parser.bytesToHex(fromHost), 2);
                    String[] replyValues = rpParser.getIsoBitValue();
                    String msg_rc = "";
                    if (replyValues[39] != null) {
                        msg_rc = replyValues[39];
                        if (!((msg_rc.equals("00")))) {
                            rpParser.setServiceId(serviceid);
                            rpParser.setMessageId((String) jmsg.get("msg_id"));
                            rpParser.setResponseCode(msg_rc);
                            JSONObject replyJSON = rpParser.parseISO();
                            MenuListResolver mlr = new MenuListResolver();
                            jroot = mlr.loadMenu(context, "000000F", replyJSON);
                            if (clientDB.isOpen()) {
                                clientDB.close();
                            }
                            return jroot;
                        }
                    }
                    String serverRef = replyValues[37];
                    String serverDate = replyValues[13];
                    String serverTime = replyValues[12];
                    String voidAmount = replyValues[4];
                    String addData = replyValues[48];
                    String saldo = "0000000000000000";
                    String fee = "        ";
                    try {
                        saldo = addData.substring(0, 16);
                        fee = addData.substring(16, 24);
                    } catch (Exception e) {
                        //pass
                    }
                    Date dt = new Date();
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy");
                    String repDate = sdf.format(dt) + "-";
                    String tmStamp = null;
                    repDate = repDate + serverDate.substring(0,2) + "-" +
                            serverDate.substring(2,4);
                    tmStamp = StringLib.toSQLiteTimestamp(repDate, serverTime);
                    double d = Double.parseDouble(voidAmount);
                    d = d/100;
                    voidAmount = StringLib.strToCurr(String.valueOf((int) d),"Rp");
                    d = Double.parseDouble(saldo);
                    d = d/100;
                    saldo = StringLib.strToCurr(String.valueOf((int) d),"Rp");
                    if (fee.replaceAll(" ","").equals("")) {
                        fee = "0";
                    }
                    d = Double.parseDouble(fee);
                    fee = StringLib.strToCurr(String.valueOf((int) d),"Rp");
                    JSONObject returnScreen = new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":[" +
                            "{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"" + voidAmount + "\",\n" +
                            "\"value\":\"" + voidAmount + "\"}]},\"comp_lbl\":\"Jumlah Void : \",\"comp_type\":\"1\",\"comp_id\":\"P00002\",\"seq\":0}," +
                            "{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"" + fee + "\",\n" +
                            "\"value\":\"" + fee + "\"}]},\"comp_lbl\":\"Fee         : \",\"comp_type\":\"1\",\"comp_id\":\"P00003\",\"seq\":1}," +
                            "{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"" + saldo + "\",\n" +
                            "\"value\":\"" + saldo + "\"}]},\"comp_lbl\":\"Saldo       : \",\"comp_type\":\"1\",\"comp_id\":\"P00004\",\"seq\":2}" +
                            "]},\"id\":\"640000F\",\n" +
                            "\"type\":\"1\",\"title\":\"Void Tarik Tunai\",\"print\":\"2\",\"print_text\":\"WF\",\"server_date\":\"" + serverDate + "\"" +
                            ",\"server_time\":\"" + serverTime + "\",\"server_ref\":\"" + serverRef + "\"},\"server_date\":\""+serverDate+"\"," +
                            "\"server_time\":\""+serverTime+"\",\"server_ref\":\""+serverRef+"\"}");
                    writeDebugLog("EDCLOG", "update (588)");
                    String q = "update edc_log set service_id = 'A64000', rran = '" + serverRef +
                            "', rqtime = '"+tmStamp+"' where log_id = '" + stanvoid + "';";
                    clientDB.execSQL(q);
                    q = "select max(log_id) newid from messagelog";
                    Cursor getnewlogid = clientDB.rawQuery(q, null);
                    String newlogid = mlogid;
                    if (getnewlogid.moveToFirst()) {
                        newlogid = String.valueOf(getnewlogid.getInt(0)+1);
                    }
                    getnewlogid.close();
                    writeDebugLog("MSGLOG", "update (590)");
                    String logUpdate = "update messagelog set response_message = '"+returnScreen.toString()+"', " +
                            "service_id = 'A64000', request_time = '" + tmStamp + "', log_id = " + newlogid +
                            " where log_id = " + mlogid;
                    clientDB.execSQL(logUpdate);
                    if (clientDB.isOpen()) {
                        clientDB.close();
                    }
                    return returnScreen;
                }
            } else {
                if (clientDB.isOpen()) {
                    clientDB.close();
                }
                return new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":[{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"Tidak terdapat data transaksi\",\n" +
                        "\"value\":\"Tidak terdapat data transaksi\"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"P00001\",\"seq\":0}]},\"id\":\"000000F\",\n" +
                        "\"type\":\"3\",\"title\":\"Void Tarik Tunai\"}}");
            }
        }
        //handler for incomplete header including unregistered terminal
        if (Arrays.asList(txElements).contains("") && !isLogon ) {
            return new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":[{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"Data transaksi tidak lengkap\",\n" +
                    "\"value\":\"Header transaksi tidak lengkap atau terminal tidak terdaftar\"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"P00001\",\"seq\":0}]},\"id\":\"000000F\",\n" +
                    "\"type\":\"3\",\"title\":\"Gagal\"}}");
        }
        // intercept reprint last transaction
        if (txElements[2].startsWith("P")) {
            writeDebugLog("MSGLOG", "read (610)");
            writeDebugLog("EDCLOG", "read (611)");
            String qLog = "select a.*, b.track2 from messagelog a left outer join edc_log b on (a.message_id=b.stan and a.service_id=b.service_id)"
                    + "where a.service_id like 'A" + txElements[2].substring(1) + "%' "
                    + "and a.message_status = '00' "
                    + "and a.print > 0 "
                    + "and (lower(b.settled) <> 't' or b.settled is null) "
                    + "and a.service_id not in ('A2A100','A29100','A23100',"
                    + "'A22000','A23000','A22100','A2B000','A2B100', 'A52100', 'A52210', 'A52220', 'A52300', 'A59000') "
                    + "order by a.log_id desc";
            Cursor cLog = clientDB.rawQuery(qLog, null);
            if (cLog.moveToFirst()) {
                String screen_value = cLog.getString(cLog.getColumnIndex("response_message"));
                String screen_trace = cLog.getString(cLog.getColumnIndex("message_id"));
                String cardUsed = cLog.getString(cLog.getColumnIndex("track2"));
                String cardType = "SWIPE (DEBIT)";
                if (!cardUsed.contains("=")) {
                    cardType = "BRIZZI CARD (FLY)";
                }
                if (cardType.equals("")) {
                    cardType = "";
                }
                if (cLog!=null) {
                    cLog.close();
                }
                JSONObject rps = new JSONObject(screen_value);
                rps.put("reprint", 1);
                rps.put("rstan", screen_trace);
                if (cardUsed!=null) {
                    if (!cardUsed.equals("")) {
                        rps.put("nomor_kartu", cardUsed);
                    }
                }
                rps.put("card_type", cardType);
                if (clientDB.isOpen()) {
                    clientDB.close();
                }
                return rps;
            } else {
                if (cLog!=null) {
                    cLog.close();
                }
                if (clientDB.isOpen()) {
                    clientDB.close();
                }
                Log.d("CEK ARYO", "1");
                return new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":[{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"Data transaksi tidak ditemukan\",\n" +
                        "\"value\":\"Data transaksi tidak ditemukan\"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"P00001\",\"seq\":0}]},\"id\":\"000000F\",\n" +
                        "\"type\":\"3\",\"title\":\"Gagal\"}}");
            }
        }
        if (txElements[2].startsWith("Q")) {
            String trace_number = (String) rqContent.get("msg_dt");
            writeDebugLog("MSGLOG", "read (656)");
            writeDebugLog("EDCLOG", "read (657)");
            String qLog = "select a.*, b.track2 from messagelog a left outer join edc_log b on (a.message_id=b.stan  and a.service_id=b.service_id)"
                    + "where cast(a.message_id as integer) = " + trace_number + " "
                    + "and a.service_id like 'A" + txElements[2].substring(1) + "%' "
                    + "and a.message_status = '00' "
                    + "and a.print > 0 "
                    + "and (lower(b.settled) <> 't' or b.settled is null) "
                    + "and a.service_id not in ('A2A100','A29100','A23100',"
                    + "'A22000','A23000','A22100','A2B000','A2B100', 'A52100', 'A52210', 'A52220', 'A52300', 'A59000') "
                    + "order by a.log_id desc";
            Cursor cLog = clientDB.rawQuery(qLog, null);
            if (cLog.moveToFirst()) {
                String screen_value = cLog.getString(cLog.getColumnIndex("response_message"));
                String screen_trace = cLog.getString(cLog.getColumnIndex("message_id"));
                String cardUsed = cLog.getString(cLog.getColumnIndex("track2"));
                String cardType = "SWIPE (DEBIT)";
                if (!cardUsed.contains("=")) {
                    cardType = "SMART CARD (FLY)";
                }
                if (cardType.equals("")) {
                    cardType = "";
                }
                if (cLog!=null) {
                    cLog.close();
                }
                if (clientDB.isOpen()) {
                    clientDB.close();
                }
                JSONObject rps = new JSONObject(screen_value);
                rps.put("reprint", 1);
                rps.put("rstan", screen_trace);
                if (cardUsed!=null) {
                    if (!cardUsed.equals("")) {
                        rps.put("nomor_kartu", cardUsed);
                    }
                }
                rps.put("card_type", cardType);
                return rps;
            } else {
                if (cLog!=null) {
                    cLog.close();
                }
                if (clientDB.isOpen()) {
                    clientDB.close();
                }
                Log.d("CEK ARYO", "2");
                return new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":[{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"Data transaksi tidak ditemukan\",\n" +
                        "\"value\":\"Data transaksi tidak ditemukan\"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"P00001\",\"seq\":0}]},\"id\":\"000000F\",\n" +
                        "\"type\":\"3\",\"title\":\"Gagal\"}}");
            }
        }
        if (txElements[2].startsWith("A5B1")||
                txElements[2].startsWith("A661")||
                txElements[2].startsWith("A751")||
                txElements[2].startsWith("A2F1")) {
            // Report Summary intercept
            String dateFilter = "";
            String tgl = "";
//            if (txElements[2].endsWith("10")||!txElements[2].startsWith("A2")) {
            if (txElements[2].endsWith("10")) {
                tgl = (String) rqContent.get("msg_dt");
                tgl = tgl.substring(4)+"-"+tgl.substring(2,4)+"-"+tgl.substring(0,2);
                dateFilter = "and date(a.rqtime)='" + tgl + "'\n";
            }
            if (txElements[3].endsWith("30")) {
                dateFilter = "and date(a.rqtime)='" + StringLib.getYYYYMMDD() + "'\n";
            }
            String modBase = "edc_log";
            String excludeList = "";
            if (txElements[2].startsWith("A2F1")) {
                modBase = "(select service_id, rc, rqtime, reversed, settled, (amount*sign) as amount from (select *, " +
                        "case service_id when 'A24100' then 1 when 'A24200' then 1 else 1 end sign " +
                        "from edc_log))";
//                excludeList = "and a.service_id not in ('A2C100', 'A2C200')\n";
                excludeList = "and (lower(a.settled) <> 't' or a.settled is null) \n" +
                        "and a.service_id not in ('A2A100','A29100','A23100'," +
                        "'A22000','A23000','A22100','A2B000','A2B100')\n";
            }
            if (txElements[2].startsWith("A6")) {
                excludeList = "and a.service_id not in ('A61000', 'A62000', 'A63000')\n";
            }
            if (txElements[2].startsWith("A7")) {
                excludeList = "and a.service_id not in ('A71001', 'A72000', 'A72001', 'A73000')\n";
            }
            if (txElements[2].startsWith("A5")) {
                excludeList = "and a.service_id not in ('A54911', 'A51410', 'A53100', 'A53211', 'A53221', 'A54921', 'A54931', 'A54941', \n" +
                        "'A54B11', 'A54A10', 'A54110', 'A54211', 'A54221', 'A54311', 'A54321', 'A54410', \n" +
                        "'A54431', 'A54433', 'A54441', 'A54443', 'A54451', 'A54453', 'A54461', 'A54510', \n" +
                        "'A54520', 'A54530', 'A54540', 'A54550', 'A54560', 'A57000', 'A57200', 'A57400', \n" +
                        "'A58000', 'A54421', 'A54423', 'A54C10', 'A54C20', 'A54C51', 'A54C52', 'A54C53', \n" +
                        "'A54C54', 'A52100', 'A52210', 'A52220', 'A52300', 'A54950', 'A54710', 'A54720', " +
                        "'A54800', 'A59000', 'A54331') \n";
            }
            String siLimit = txElements[2].substring(0,2);
            String nGrand = "0";
            String jGrand = "0";
            writeDebugLog("EDCLOG", "read grand (744)");
            String qGrand = ""
                    + "select sum(a.amount) tot, count(*) jml from " + modBase + " a left outer join service b\n" +
                    "on (a.service_id = b.service_id)\n" +
                    "where a.rc= '00'\n" +
                    dateFilter +
//                    "and a.amount is not null\n" +
//                    "and a.amount <> 0\n" +
                    "and (lower(a.reversed) <> 't' or a.reversed is null)\n" +
                    "and a.service_id like '" + siLimit + "%'\n" +
                    excludeList;
            writeDebugLog("RPT", "Query Grand \n" + qGrand);
            Cursor cGrand = clientDB.rawQuery(qGrand, null);
            if (cGrand.moveToFirst()) {
                nGrand = String.valueOf(cGrand.getInt(cGrand.getColumnIndex("tot")));
                jGrand = String.valueOf(cGrand.getInt(cGrand.getColumnIndex("jml")));
                if (nGrand.matches("-?\\d+(\\.\\d+)?")) {
                    double d = Double.parseDouble(nGrand);
                    DecimalFormatSymbols idrFormat = new DecimalFormatSymbols(Locale.getDefault());
                    idrFormat.setDecimalSeparator(',');
                    DecimalFormat formatter = new DecimalFormat("###,###,##0", idrFormat);
                    d = d/100;
                    nGrand = formatter.format(d);
                }
            }
            if (cGrand!=null) {
                cGrand.close();
            }
            writeDebugLog("EDCLOG", "read (772)");
            String qLog = ""
                    + "select (case when b.service_name is null then a.service_id else b.service_name end)" +
                    " as service_name, count(*) jml, sum(a.amount) tot from edc_log a left outer join service b\n" +
                    "on (a.service_id = b.service_id)\n" +
                    "where a.rc= '00'\n" +
                    dateFilter +
//                    "and a.amount is not null\n" +
//                    "and a.amount > 0\n" +
                    "and (lower(a.reversed) <> 't' or a.reversed is null)\n" +
                    "and a.service_id like '" + siLimit + "%'\n" +
                    excludeList +
                    "group by b.service_name;";
            writeDebugLog("RPT", "Query Data \n" + qLog);
            Cursor cLog = clientDB.rawQuery(qLog, null);
            if (cLog.moveToFirst()) {
                String cmp = "";
                int sq = 0;
                String ssq = String.valueOf(sq);
                cmp += "{\"visible\":true,\"comp_values\":"
                        + "{\"comp_value\":["
                        + "{\"print\":\"----------------------------------------\",\n"
                        + "\"value\":\"----------------------------------------\"}"
                        + "]},\"comp_lbl\":\"\",\"comp_type\":\"1\","
                        + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"}";
                sq++;
                ssq = String.valueOf(sq);
                cmp += ",";
                cmp += "{\"visible\":true,\"comp_values\":"
                        + "{\"comp_value\":["
                        + "{\"print\":\"TRANSAKSI           COUNT         AMOUNT\",\n"
                        + "\"value\":\"TRANSAKSI           COUNT         AMOUNT\"}"
                        + "]},\"comp_lbl\":\"\",\"comp_type\":\"1\","
                        + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"}";
                sq++;
                ssq = String.valueOf(sq);
                cmp += ",";
                cmp += "{\"visible\":true,\"comp_values\":"
                        + "{\"comp_value\":["
                        + "{\"print\":\"----------------------------------------\",\n"
                        + "\"value\":\"----------------------------------------\"}"
                        + "]},\"comp_lbl\":\"\",\"comp_type\":\"1\","
                        + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"}";
                sq++;
                ssq = String.valueOf(sq);
                do {
                    if (!cmp.equals("")) {
                        cmp += ",";
                    }
                    String cval = String.valueOf(cLog.getInt(cLog.getColumnIndex("tot")));
                    if (cval.matches("-?\\d+(\\.\\d+)?")) {
                        double d = Double.parseDouble(cval);
                        DecimalFormatSymbols idrFormat = new DecimalFormatSymbols(Locale.getDefault());
                        idrFormat.setDecimalSeparator(',');
                        DecimalFormat formatter = new DecimalFormat("###,###,##0", idrFormat);
                        d = d/100;
                        cval = formatter.format(d);
                        cval = StringLib.strToCurr(String.valueOf(d), "Rp");
                    }
                    String clab = cLog.getString(cLog.getColumnIndex("service_name"));
                    if (clab.startsWith("Transaksi ")) {
                        clab = clab.substring(10);
                    }
                    int ccount = cLog.getInt(cLog.getColumnIndex("jml"));
                    ssq = String.valueOf(sq);
                    sq++;
                    cmp += "{\"visible\":true,\"comp_values\":"
                        + "{\"comp_value\":["
                        + "{\"print\":\"" + cval + "\",\n"
                        + "\"value\":\"" + cval + "\"}"
                        + "]},\"comp_lbl\":\"" + clab + " :"+ String.valueOf(ccount) +"\",\"comp_type\":\"1\","
                        + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"}";
                } while (cLog.moveToNext());
                ssq = String.valueOf(sq);
                cmp += ",";
                cmp += "{\"visible\":true,\"comp_values\":"
                    + "{\"comp_value\":["
                    + "{\"print\":\"----------------------------------------\",\n"
                    + "\"value\":\"----------------------------------------\"}"
                    + "]},\"comp_lbl\":\"\",\"comp_type\":\"1\","
                    + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"}";
                sq++;
                ssq = String.valueOf(sq);
                nGrand = StringLib.strToCurr(nGrand.replaceAll("[,.]",""),"Rp");
                cmp += ",";
                cmp += "{\"visible\":true,\"comp_values\":"
                    + "{\"comp_value\":["
                    + "{\"print\":\"[B]"+nGrand+"\",\n"
                    + "\"value\":\"[B]"+nGrand+"\"}"
                    + "]},\"comp_lbl\":\"[B]GRAND TOTAL :" + jGrand + "\",\"comp_type\":\"1\","
                    + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"}";
                sq++;
                ssq = String.valueOf(sq);
                cmp += ",";
                cmp += "{\"visible\":true,\"comp_values\":"
                        + "{\"comp_value\":["
                        + "{\"print\":\"----------------------------------------\",\n"
                        + "\"value\":\"----------------------------------------\"}"
                        + "]},\"comp_lbl\":\"\",\"comp_type\":\"1\","
                        + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"}";
                if (cLog!=null) {
                    cLog.close();
                }
                return new JSONObject("{\"screen\":{\"ver\":\"1.5\",\"print\":\"1\",\"print_text\":\"RPT" + tgl
                        + "\",\"comps\":{\"comp\":["
                        + cmp
                        + "]},\"id\":\"RS00001\",\n" +
                        "\"type\":\"1\",\"title\":\"Summary Report\"}}");
            } else {
                if (cLog!=null) {
                    cLog.close();
                }

                Log.d("CEK ARYO", "3");
                return new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":[{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"Data transaksi tidak ditemukan\",\n" +
                        "\"value\":\"Data transaksi tidak ditemukan\"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"P00001\",\"seq\":0}]},\"id\":\"000000F\",\n" +
                        "\"type\":\"3\",\"title\":\"Gagal\"}}");
            }
        }
        //start mod report
        if (txElements[2].startsWith("A5B2")||
                txElements[2].startsWith("A662")||
                txElements[2].startsWith("A752") ||
                txElements[2].startsWith("A2F2"))  {
            // Report Audit intercept
            String dateFilter = "";
            String tgl = "";
            if (txElements[2].endsWith("10")) {
                tgl = (String) rqContent.get("msg_dt");
                tgl = tgl.substring(4)+"-"+tgl.substring(2,4)+"-"+tgl.substring(0,2);
                dateFilter = "and date(a.rqtime)='" + tgl + "'\n";
            }
            if (txElements[3].endsWith("30")) {
                dateFilter = "and date(a.rqtime)='" + StringLib.getYYYYMMDD() + "'\n";
            }
            String excludeList = "";
            if (txElements[2].startsWith("A2F2")) {
//                excludeList = "and a.service_id not in ('A2C100', 'A2C200')\n";
                excludeList = "and (lower(a.settled) <> 't' or a.settled is null) \n" +
                        "and a.service_id not in ('A2A100','A29100','A23100'," +
                        "'A22000','A23000','A22100','A2B000','A2B100')\n";
            }
            if (txElements[2].startsWith("A6")) {
                excludeList = "and a.service_id not in ('A61000', 'A62000', 'A63000')\n";
            }
            if (txElements[2].startsWith("A7")) {
                excludeList = "and a.service_id not in ('A71001', 'A72000', 'A72001', 'A73000')\n";
            }
            if (txElements[2].startsWith("A5")) {
                excludeList = "and a.service_id not in ('A54911', 'A51410', 'A53100', 'A53211', 'A53221', 'A54921', 'A54931', 'A54941', \n" +
                        "'A54B11', 'A54A10', 'A54110', 'A54211', 'A54221', 'A54311', 'A54321', 'A54410', \n" +
                        "'A54431', 'A54433', 'A54441', 'A54443', 'A54451', 'A54453', 'A54461', 'A54510', \n" +
                        "'A54520', 'A54530', 'A54540', 'A54550', 'A54560', 'A57000', 'A57200', 'A57400', \n" +
                        "'A58000', 'A54421', 'A54423', 'A54C10', 'A54C20', 'A54C51', 'A54C52', 'A54C53', \n" +
                        "'A54C54', 'A52100', 'A52210', 'A52220', 'A52300', 'A54950', 'A54710', 'A54720', " +
                        "'A54800', 'A59000', 'A54331') \n";
            }
            String siLimit = txElements[2].substring(0,2);
            writeDebugLog("MSGLOG", "read (923)");
            writeDebugLog("EDCLOG", "read (924)");
            String qList = ""
                    + "select a.rqtime,(case when b.service_name is null then a.service_id else"
                    + " b.service_name end) as service_name,substr(track2,1,16) cno, substr(track2,18,21) cexp,"
                    + "a.stan, a.rc, a.amount, c.response_message from edc_log a left outer join service b\n" +
                    "on (a.service_id = b.service_id) \n" +
                    "left outer join messagelog c \n" +
                    "on (a.stan = c.message_id and date(a.rqtime)=date(c.request_time) and a.service_id=c.service_id) \n" +
                    "where a.rc= '00'\n" +
                    dateFilter + " \n" +
//                    "and a.amount is not null\n" +
//                    "and a.amount > 0\n" +
                    "and (lower(a.reversed) <> 't' or a.reversed is null)\n" +
                    "and a.service_id like '" + siLimit + "%'\n" +
                    excludeList;
            writeDebugLog("RPT", "Query Detail \n" + qList);
            List<PrintSize> data = new ArrayList<>();
            data.add(new PrintSize(FontSize.EMPTY, "\n"));
            data.add(new PrintSize(FontSize.TITLE, "Detail Report\n"));
            data.add(new PrintSize(FontSize.EMPTY, "\n"));
            Cursor cList = clientDB.rawQuery(qList, null);
            if (cList.moveToFirst()) {
                String cmp = "";
                int sq = 0;
                int dataCount = 0;
//                String clab = "No\tTanggal\tJam\tTransaksi";
//                String cval = "Kartu\tSTAN\tRC\tNominal";
                data.add(new PrintSize(FontSize.NORMAL, ""));
                data.add(new PrintSize(FontSize.NORMAL, "----------------------------------------\n"));
                String ssq = String.valueOf(sq);
                sq++;
                cmp += "{\"visible\":true,\"comp_values\":"
                    + "{\"comp_value\":["
                    + "{\"print\":\"----------------------------------------\",\n"
                    + "\"value\":\"----------------------------------------\"}"
                    + "]},\"comp_lbl\":\"\",\"comp_type\":\"1\","
                    + "\"comp_id\":\"R00002\",\"seq\":"+ssq+"}";
                do {
                    if (!cmp.equals("")) {
                        cmp += ",";
                    }
                    String cNo = ssq;
                    String cTgl = cList.getString(cList.getColumnIndex("rqtime")).substring(0,10);
                    String cJam = cList.getString(cList.getColumnIndex("rqtime")).substring(11);
                    String cTx = cList.getString(cList.getColumnIndex("service_name"));
                    if (cTx.startsWith("Transaksi ")) {
                        cTx = cTx.substring(10);
                    }
                    String cCard = cList.getString(cList.getColumnIndex("cno"));
                    String cExp = cList.getString(cList.getColumnIndex("cexp"));
                    if (cExp!=null) {
                        if (!cExp.equals("0000") && !cExp.equals("")) {
                            cExp = cExp.substring(0,2) + "/" + cExp.substring(2,4);
                        } else {
                            cExp = "";
                        }
                    } else {
                        cExp = "";
                    }
                    String cStan = cList.getString(cList.getColumnIndex("stan"));
                    String cRc = cList.getString(cList.getColumnIndex("rc"));
                    String cAmo = String.valueOf(cList.getInt(cList.getColumnIndex("amount")));
                    String cSResp = cList.getString(cList.getColumnIndex("response_message"));
                    String cSRef = "000000000000";
                    String cAppr = "00000000";
                    if (cSResp!=null) {
                        if (!cSResp.equals("")) {
                            JSONObject respData = new JSONObject(cSResp);
                            if (respData.has("server_ref")) {
                                cSRef = respData.getString("server_ref");
                            }
                            if (respData.has("server_appr")) {
                                cAppr = respData.getString("server_appr");
                            }
                        }
                    }
                    if (cAmo.matches("-?\\d+(\\.\\d+)?")) {
                        double d = Double.parseDouble(cAmo);
                        DecimalFormatSymbols idrFormat = new DecimalFormatSymbols(Locale.getDefault());
                        idrFormat.setDecimalSeparator(',');
                        DecimalFormat formatter = new DecimalFormat("###,###,##0", idrFormat);
                        d = d/100;
//                        if (!cTx.startsWith("Pembayaran PLN")) {
//                            d = d/100;
//                        }
                        cAmo = formatter.format(d);
                        cAmo = StringLib.strToCurr(String.valueOf(d), "Rp");
                    }
//                    clab = cNo+"\t"+cTgl+"\t"+cJam+"\t"+cTx;
//                    cval = cCard+"\t"+cStan+"\t"+cRc+"\t"+cAmo;
                    data.add(new PrintSize(FontSize.NORMAL, cTx + "|:"));
                    data.add(new PrintSize(FontSize.NORMAL,":|"+cAmo+"\n"));
                    data.add(new PrintSize(FontSize.NORMAL, cCard + "|:"));
                    data.add(new PrintSize(FontSize.NORMAL,":|"+cExp+"\n"));
                    data.add(new PrintSize(FontSize.NORMAL, "STAN : "+cStan));
                    data.add(new PrintSize(FontSize.NORMAL, "\n"));
                    data.add(new PrintSize(FontSize.NORMAL, "TGL  : "+cTgl+"|:"));
                    data.add(new PrintSize(FontSize.NORMAL, ":|JAM  : "+cJam+"\n"));
                    data.add(new PrintSize(FontSize.NORMAL, "REF# : " + cSRef + "|:"));
                    data.add(new PrintSize(FontSize.NORMAL, ":|APPR : "+cAppr+"\n"));
                    data.add(new PrintSize(FontSize.NORMAL, ""));
                    data.add(new PrintSize(FontSize.NORMAL, "----------------------------------------\n"));
                    ssq = String.valueOf(sq);
                    sq++;
                    cmp += "{\"visible\":true,\"comp_values\":"
                            + "{\"comp_value\":["
                            + "{\"print\":\":|" + cAmo + "\",\n"
                            + "\"value\":\":|" + cAmo + "\"}"
                            + "]},\"comp_lbl\":\"" + cTx + "|:\",\"comp_type\":\"1\","
                            + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"},";
                    ssq = String.valueOf(sq);
                    sq++;
                    cmp += "{\"visible\":true,\"comp_values\":"
                            + "{\"comp_value\":["
                            + "{\"print\":\":|" + cExp + "\",\n"
                            + "\"value\":\":|" + cExp + "\"}"
                            + "]},\"comp_lbl\":\"" + cCard + "|:\",\"comp_type\":\"1\","
                            + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"},";
                    ssq = String.valueOf(sq);
                    sq++;
                    cmp += "{\"visible\":true,\"comp_values\":"
                            + "{\"comp_value\":["
                            + "{\"print\":\"\",\n"
                            + "\"value\":\"\"}"
                            + "]},\"comp_lbl\":\"STAN : "+cStan+"\",\"comp_type\":\"1\","
                            + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"},";
                    ssq = String.valueOf(sq);
                    sq++;
                    cmp += "{\"visible\":true,\"comp_values\":"
                            + "{\"comp_value\":["
                            + "{\"print\":\":|JAM  : " + cJam + "\",\n"
                            + "\"value\":\":|JAM  : " + cJam + "\"}"
                            + "]},\"comp_lbl\":\"TGL  : " + cTgl + "|:\",\"comp_type\":\"1\","
                            + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"},";
                    ssq = String.valueOf(sq);
                    sq++;
                    cmp += "{\"visible\":true,\"comp_values\":"
                            + "{\"comp_value\":["
                            + "{\"print\":\":|APPR : 000000  \",\n"
                            + "\"value\":\":|APPR : 000000  \"}"
                            + "]},\"comp_lbl\":\"REF# : " + cSRef + "|:\",\"comp_type\":\"1\","
                            + "\"comp_id\":\"R00001\",\"seq\":"+ssq+"},";
                    ssq = String.valueOf(sq);
                    sq++;
                    cmp += "{\"visible\":true,\"comp_values\":"
                            + "{\"comp_value\":["
                            + "{\"print\":\"----------------------------------------\",\n"
                            + "\"value\":\"----------------------------------------\"}"
                            + "]},\"comp_lbl\":\"\",\"comp_type\":\"1\","
                            + "\"comp_id\":\"R00002\",\"seq\":"+ssq+"}";
                    dataCount++;
                } while (cList.moveToNext());
//                data.add(new PrintSize(FontSize.NORMAL, "START FOOTER"));
//                data.add(new PrintSize(FontSize.EMPTY, "\n"));
//                data.add(new PrintSize(FontSize.NORMAL, "Informasi lebih lanjut, silahkan hubungi"));
//                data.add(new PrintSize(FontSize.NORMAL, "\n"));
//                data.add(new PrintSize(FontSize.NORMAL, "Call BRI di 14017, 021-500017,"));
//                data.add(new PrintSize(FontSize.NORMAL, "\n"));
//                data.add(new PrintSize(FontSize.NORMAL, "atau 021-57987400"));
//                data.add(new PrintSize(FontSize.NORMAL, "\n"));
//                data.add(new PrintSize(FontSize.EMPTY, "\n"));
//                data.add(new PrintSize(FontSize.NORMAL, "*** Terima Kasih ***"));
//                data.add(new PrintSize(FontSize.NORMAL, "\n"));
                setHasPrintData(true);
                setPrintData(data);
                setPrintText("RPD"+tgl);
                if (cList!=null) {
                    cList.close();
                }
                return new JSONObject("{\"screen\":{\"ver\":\"1.5\",\"comps\":{\"comp\":[{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"Mencetak "+dataCount+" data transaksi\",\n" +
                        "\"value\":\"Mencetak "+dataCount+" data transaksi\"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"P00001\",\"seq\":0}]},\"id\":\"RPTND0F\",\n" +
                        "\"type\":\"2\",\"title\":\"Detail Report\"}}");
//                return new JSONObject("{\"screen\":{\"ver\":\"1.5\",\"print\":\"1\",\"print_text\":\"RPD" + tgl
//                        + "\",\"comps\":{\"comp\":["
//                        + cmp
//                        + "]},\"id\":\"RD00001\",\n" +
//                        "\"type\":\"1\",\"title\":\"Detail Report\"}}");
            } else {
                if (cList!=null) {
                    cList.close();
                }
                Log.d("CEK ARYO", "4");
                return new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":[{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"Data transaksi tidak ditemukan\",\n" +
                        "\"value\":\"Data transaksi tidak ditemukan\"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"P00001\",\"seq\":0}]},\"id\":\"000000F\",\n" +
                        "\"type\":\"3\",\"title\":\"Gagal\"}}");
            }
        }
        //end mod report
        String qServ = "select * from service "
                + "where service_id = '" + txElements[2] + "'";
        Cursor cServ = clientDB.rawQuery(qServ, null); //msgService
        /*
        String qTerm = "select * from terminal "
                + "where terminal_imei = '"+txElements[1]+"'";
        Cursor cTerm = clientDB.rawQuery(qTerm, null); //termList
                */
        String[] tmid = new String[2];// = getTerminalMerchantId(rqElements[1]);
        tmid[0] = "00000023";
        tmid[1] = "000001210000020";
        // update stan
        if (msgSequence==0&&!predefined_stan) {
            String getStanSeq = "select seq msgSequence from holder";
            Cursor stanSeq = clientDB.rawQuery(getStanSeq, null);
            if (stanSeq != null) {
                stanSeq.moveToFirst();
                msgSequence = stanSeq.getInt(0);
            }
            stanSeq.close();
        }
        //check reversal stack
//        String[] revData = EDCLog.getLastRevStatus();
//        if (revData[0].equals("1")) {
//            jmsg.put("msg_rc", "05");
//            jmsg.put("msg_resp", "Memproses pending reversal, silahkan coba beberapa saat lagi");
//            MenuListResolver mlr = new MenuListResolver();
//            //Handle Reversal
//            int elid = Integer.parseInt(revData[1]);
//            Thread doReversal = new Thread(new handleReversal(context, revData[2], EDCLog, elid));
//            doReversal.start();
//            return mlr.loadMenu(context, "000000F", jmsg);
//        }
        //end of check reversal stack
        String trace_no = generateStan();
        //create message logger
        writeDebugLog("MSGLOG", "read seq (1141)");
        String getLogId = "select max(log_id) nextseq from messagelog ";
        Cursor cLogId = clientDB.rawQuery(getLogId, null);
        int logId;
        if (cLogId.moveToFirst()) {
            logId = cLogId.getInt(cLogId.getColumnIndex("nextseq"));
            logId += 1;
        } else {
            logId = 1;
        }
        cLogId.close();

        writeDebugLog("MSGLOG", "insert (1153)");
        String iMsgLog = "insert or replace into messagelog "
                + "(message_id, service_id, terminal_id, request_time, log_id, "
                + "request_message) values ('" + trace_no + "', "
//                + "request_message) values ('" + txElements[0] + "', "
                + "'" + txElements[2] + "', "
                + "'" + txElements[1] + "', '" + StringLib.getSQLiteTimestamp()
                + "', " + String.valueOf(logId) + ", '"
                + content + "')";
        clientDB.execSQL(iMsgLog);
        //commit changes

        //get request data
        String reqData = getData(rqContent);
        if (reqData.equals("") && !isLogon) {
            return new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":[{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"Data transaksi tidak lengkap\",\n" +
                    "\"value\":\"Data transaksi tidak lengkap\"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"P00001\",\"seq\":0}]},\"id\":\"000000F\",\n" +
                    "\"type\":\"3\",\"title\":\"Gagal\"}}");
        }
        //do process here
        //prepare query for service meta
        String qMeta = "select * from service_meta "
                + "where service_id = '" + serviceid + "' "
                + "and influx = 1 ";
        Cursor cMeta = clientDB.rawQuery(qMeta, null);
        //get query result
        int metaCount = cMeta.getCount();
        //handler for empty meta
        if (metaCount < 1) {
            Log.e("TX", "Service Meta request not found.");
        }
        //handler for unmatch field count
        String[] requestData = reqData.split("\\|");
        if (requestData.length != metaCount) {
//            if (txElements[2].startsWith("A53"))
            Log.e("TX", "Request field count does not matched");
            jmsg.put("msg_rc", "05");
            jmsg.put("msg_resp", "Jumlah data transaksi tidak sesuai");
            MenuListResolver mlr = new MenuListResolver();
            JSONObject replyJSON = mlr.loadMenu(context, "000000F", jmsg);
            return replyJSON;
        }

        // temp var for validation
        String[] serviceMeta = new String[metaCount];
        if (cMeta.moveToFirst()) {
            do {
                String metaIsoBit = cMeta.getString(cMeta.getColumnIndex("iso_bit_uid"));
                int seqMeta = cMeta.getInt(cMeta.getColumnIndex("seq"));
                String metaId = cMeta.getString(cMeta.getColumnIndex("meta_id"));
                if (metaIsoBit != null) {
                    if (metaIsoBit.equals("V")) {
                        serviceMeta[seqMeta] = "XV " + metaId;
                    } else {
                        serviceMeta[seqMeta] = metaId;
                    }
                } else {
                    serviceMeta[seqMeta] = metaId;
                }
            } while (cMeta.moveToNext());

        }

        //compose internal message
        String prevData = "";
        for (int i = 0; i < metaCount; i++) {
            if (serviceMeta[i].length() > 2) {
                if (serviceMeta[i].substring(0, 3).equals("XV ") && !requestData[i].equals(prevData)) {
                    Log.e("TX", "Cross validation failed.");
                    jmsg.put("msg_rc", "05");
                    jmsg.put("msg_resp", serviceMeta[i].substring(3) + " tidak sama dengan " + serviceMeta[i - 1]);
                    MenuListResolver mlr = new MenuListResolver();
                    JSONObject replyJSON = mlr.loadMenu(context, "000000F", jmsg);
                    return replyJSON;
                }
            }
            prevData = requestData[i];
            String iServiceData = "insert or replace into service_data (message_id, name, value) "
                    + "values ('" + jmsg.get("msg_id") + "', '" + serviceMeta[i] + "', '"
                    + requestData[i] + "')";
            clientDB.execSQL(iServiceData);
        }
        //tosend
        String toParse = jmsg.get("msg_id") + "|" + txElements[3] + "|" + txElements[4];
        if (Arrays.asList(serviceMeta).contains("id")) {
            writeDebugLog("COPY", "Service Data from : " + requestData[Arrays.asList(serviceMeta).indexOf("id")] + "to" + jmsg.get("msg_id"));
            String uPrev = "insert into service_data (message_id, name, value) "
                    + "select '" + jmsg.get("msg_id") + "', name, value from service_data "
                    + "where message_id = '" + requestData[Arrays.asList(serviceMeta).indexOf("id")] + "' "
                    + "and name not in (select name from service_data "
                    + "where message_id = '" + jmsg.get("msg_id") + "')";
            writeDebugLog("with", uPrev);
            clientDB.execSQL(uPrev);
        }
        //send to parser
        cServ.moveToFirst();
        boolean directReply = cServ.getString(cServ.getColumnIndex("is_to_core")).equals("f");
        JSONObject replyJSON = null;
        if (directReply) {
            String screenResponse = cServ.getString(cServ.getColumnIndex("screen_response"));
            replyJSON = new JSONObject();
            replyJSON.put("messageId",jmsg.getString("msg_id"));
            for (int r = 0; r < metaCount; r++) {
                String currentValue = requestData[r];
                if ((currentValue.matches("-?\\d+(\\.\\d+)?"))
                        &&(serviceMeta[r].startsWith("nom")
                        ||serviceMeta[r].startsWith("sal")
                        ||serviceMeta[r].startsWith("amo"))) {
                    currentValue = currentValue + "00";
                }
                if (serviceMeta[r].equals("nopol")) {
                    currentValue = currentValue.toUpperCase();
                }
                replyJSON.put(serviceMeta[r], currentValue);
            }
            if (serviceid.equals("A90000")) {
                replyJSON.put("nama","DADANG");
                replyJSON.put("merek","HONDA");
                replyJSON.put("type","CITY GM2 1.5 S AT");
                replyJSON.put("rakit","2009");
                replyJSON.put("masa","11/30/2016");
                replyJSON.put("rangka","MRGHM26409P920479");
                replyJSON.put("mesin","L15A71811535");
                replyJSON.put("nom_bayar", "311300000");
            }
            if (serviceid.equals("A91000")) {
                String msid = requestData[Arrays.asList(serviceMeta).indexOf("id")];
                String qry = "select * from service_data where message_id = '" + msid + "' and name = 'nopol'";
                Cursor cNopol = clientDB.rawQuery(qry, null);
                if (cNopol.moveToFirst()) {
                    String nopol = cNopol.getString(cNopol.getColumnIndex("value"));
                    replyJSON.put("nopol", nopol.toUpperCase());
                }
                cNopol.close();
                replyJSON.put("nama","DADANG");
                replyJSON.put("merek","HONDA");
                replyJSON.put("type","CITY GM2 1.5 S AT");
                replyJSON.put("rakit","2009");
                replyJSON.put("masa","11/30/2016");
                replyJSON.put("rangka","MRGHM26409P920479");
                replyJSON.put("mesin","L15A71811535");
                replyJSON.put("nom_bayar", "311300000");
                replyJSON.put("nom_admin", "500000");
                replyJSON.put("nom_total", "311800000");
                replyJSON.put("kode", "0123456789ABCDEF");
            }
            try {
                Thread.sleep(700);
            } catch (Exception ex) {
                //pass
            }
            MenuListResolver mlr = new MenuListResolver();
            jroot = mlr.loadMenu(context, screenResponse, replyJSON);
            return jroot;
        }

        ISO8583Parser rqParser = new ISO8583Parser(context, "6000070000", serviceid, toParse, 1, trace_no);
        if (!serviceid.equals("A2C200")) {
            String uStanSeq = "update holder set "
                    + "seq = " + msgSequence;
            writeDebugLog("UPDATING", "HOLDER (1255)");
            writeDebugLog("By ", serviceid);
            clientDB.execSQL(uStanSeq);
        }
        if (serviceid.equals("A54322")||serviceid.equals("A54312")||serviceid.equals("A54331")) {
            EDCLog.setIgnoreReplyAmount(true);
        }
        elogid = EDCLog.writePreLog(
                rqParser.getIsoBitValue(),
                serviceid,
                (String) jmsg.get("msg_id"));
        byte[] toHost = rqParser.parseJSON();

        // send to host
//        byte[] fromHost = sendMessage(context, toHost);
        int cLen = toHost.length;
        byte[] hLen = Arrays.copyOfRange(ByteBuffer.allocate(4).putInt(cLen).array(), 2, 4);
        byte[] formattedContent = ByteBuffer.allocate(2 + cLen).put(hLen).put(toHost).array();
        hsToHost = ISO8583Parser.bytesToHex(formattedContent);
        SharedPreferences preferences = ctx.getSharedPreferences(CommonConfig.SETTINGS_FILE, Context.MODE_PRIVATE);
        String host_ip = preferences.getString("ip", CommonConfig.DEV_SOCKET_IP);
        int host_port = Integer.valueOf(preferences.getString("port", CommonConfig.DEV_SOCKET_PORT));
        AsyncMessageWrapper amw = new AsyncMessageWrapper(host_ip, host_port, hsToHost);
        byte[] fromHost = sendMessage(amw);

        Boolean txState = false;
        boolean reversable = rqParser.isReversable();
        if (fromHost == null && !isBrizziVoid) {
            jmsg.put("msg_rc", "05");
            String reversalInfo = "";
            if (reversable) {
                reversalInfo = "\nMengirim reversal";
            }
            jmsg.put("msg_resp", "Tidak dapat terhubung ke server" + reversalInfo);
            MenuListResolver mlr = new MenuListResolver();
            replyJSON = mlr.loadMenu(context, "000000F", jmsg);
            //Handle Reversal
            if (reversable) {
                Thread doReversal = new Thread(new handleReversal(context, hsToHost, EDCLog, elogid));
                try {
                    doReversal.start();
                } catch (Exception er) {
                    //pass cannot send reversal due invalid data
                }
            }
            return replyJSON;
        } else {
            if (fromHost==busyResponse || ISO8583Parser.bytesToHex(fromHost).equals("FF")) {
                jmsg.put("msg_rc", "05");
                jmsg.put("msg_resp", "Koneksi sedang sibuk, coba beberapa saat lagi");
                MenuListResolver mlr = new MenuListResolver();
                replyJSON = mlr.loadMenu(context, "000000F", jmsg);
                return replyJSON;
            }
            ISO8583Parser rpParser = null;
            try {
                rpParser = new ISO8583Parser(context, "6000070000", ISO8583Parser.bytesToHex(fromHost), 2);
            } catch (Exception pe) {
                jmsg.put("msg_rc", "05");
                jmsg.put("msg_resp", "Invalid server response");
                MenuListResolver mlr = new MenuListResolver();
                replyJSON = mlr.loadMenu(context, "000000F", jmsg);
                return replyJSON;
            }
            EDCLog.writePostLog(rpParser.getIsoBitValue(), elogid);
            String[] replyValues = rpParser.getIsoBitValue();
            String mid = replyValues[11];
            String msg_rc = "";
            txState = true;
            if (replyValues[39] != null) {
                msg_rc = replyValues[39];
                if (!((msg_rc.equals("00"))||(msg_rc.equals("02"))||(msg_rc.equals("68")))) {
                    txState = false;
                }
            }
            rpParser.setServiceId(serviceid);
            rpParser.setMessageId((String) jmsg.get("msg_id"));
            rpParser.setResponseCode(msg_rc);
            replyJSON = rpParser.parseISO();
        }
        //mark
        if (isBrizziVoid) {
            writeDebugLog("EDCLOG", "update void (1340)");
            String q = "update edc_log set rran = 'o' where log_id = '" + stanvoid + "';";
            clientDB.execSQL(q);
        }
        MenuListResolver mlr = new MenuListResolver();
        String msgStatus = "";
        if (replyJSON.has("msg_rc")
                &&!(serviceid.equals("A54322")&&(txState))
                &&!(serviceid.equals("A56000")&&(txState))
                ) {
            //jroot.put("msg", jmsg);
            if (((String) replyJSON.get("msg_rc")).equals("00")) {
                jroot = mlr.loadMenu(context, "000000D", replyJSON);
            } else {
                jroot = mlr.loadMenu(context, "000000F", replyJSON);
            }
            msgStatus = (String) jmsg.get("msg_rc");
        } else {
            Iterator replyKeys = replyJSON.keys();
            while (replyKeys.hasNext()) {
                String k = (String) replyKeys.next();
                String uData = "insert or replace into service_data(message_id, name, value) "
                        + "values ('" + replyJSON.get("messageId") + "', '" + k + "', '"
                        + replyJSON.get(k) + "')";
                clientDB.execSQL(uData);
            }
            cServ.moveToFirst();
            String screenResponse = cServ.getString(cServ.getColumnIndex("screen_response"));
            String updReplyData = "select * from service_data "
                    + "where message_id = '" + replyJSON.get("messageId") + "' ";
            if (replyJSON.has("id")) {
                updReplyData += "or message_id = '" + replyJSON.get("id") + "' ";
            }
            Cursor cRD = clientDB.rawQuery(updReplyData, null);
            if (cRD.moveToFirst()) {
                do {
                    replyJSON.put(cRD.getString(cRD.getColumnIndex("name")),
                            cRD.getString(cRD.getColumnIndex("value")));
                } while (cRD.moveToNext());
            }
            cRD.close();
            writeDebugLog("RESP_DATA", replyJSON.toString());
            if (serviceid.equals("A54312")) {
                String tunggakan = ((String) replyJSON.get("tunggakan")).trim();
                if (tunggakan.matches("-?\\d+(\\.\\d+)?")) {
                    int tgk = Integer.parseInt(tunggakan);
                    if (tgk<1) {
                        jroot = mlr.loadMenu(context, "543120E", replyJSON);
                    } else {
                        jroot = mlr.loadMenu(context, screenResponse, replyJSON);
                    }
                } else {
                    jroot = mlr.loadMenu(context, screenResponse, replyJSON);
                }
            } else if (serviceid.equals("A54322")) {
                String txrc = "00";
                if (replyJSON.has("msg_rc")) {
                    txrc = (String) replyJSON.get("msg_rc");
                }
                if (txrc.equals("02")) {
                    jroot = mlr.loadMenu(context, "543220E", replyJSON);
                } else if (txrc.equals("68")) {
                    jroot = mlr.loadMenu(context, "543220E", replyJSON);
                } else {
                    jroot = mlr.loadMenu(context, screenResponse, replyJSON);
                }
            } else if (serviceid.equals("A56000")) {
                String txrc = "00";
                if (replyJSON.has("msg_rc")) {
                    txrc = (String) replyJSON.get("msg_rc");
                }
                if (txrc.equals("68")) {
                    jroot = mlr.loadMenu(context, "560000E", replyJSON);
                } else {
                    jroot = mlr.loadMenu(context, screenResponse, replyJSON);
                }
            } else if (serviceid.equals("A56100")) {
                String txrc = (String) replyJSON.get("tx_rc");
                if (txrc.equals("68")) {
                    jroot = mlr.loadMenu(context, "561000E", replyJSON);
                } else {
                    jroot = mlr.loadMenu(context, screenResponse, replyJSON);
                }
            } else if (serviceid.equals("A54A10")) {
                String pay_mode = (String) replyJSON.get("pay_stat");
                if (pay_mode.equals("Y")) {
                    jroot = mlr.loadMenu(context, "54A111F", replyJSON);
                } else {
                    jroot = mlr.loadMenu(context, screenResponse, replyJSON);
                }
//            } else if (serviceid.equals("A58100")) {
//                String periode = (String) replyJSON.get("periode");
//                String pBulan = periode.substring(0,2);
//                String pTahun = periode.substring(2);
//                periode = pBulan + "/" + pTahun.substring(2);
//                replyJSON.put("periode", periode);
            } else {
                if (isLogon) {
                    //save key here
//                    try {
//                        PinPadInterface.open();
//                        String wk = (String) replyJSON.getString("work_key");
//                        //override wk
////                        wk = "376EB729FB11373BC0F097ECE49F6A25";
//                        if (wk.length()==16) {
//                            wk = wk+wk;
//                        }
//                        writeDebugLog("LOGON", wk);
//                        byte[] newKey = ISO8583Parser.hexStringToByteArray(wk);
//                        int ret = PinPadInterface.updateUserKey(0,0, newKey, newKey.length);
//                        writeDebugLog("LOGON", "Status : "+String.valueOf(ret));
//                    } catch (Exception e) {
//                        //teu bisa update
//                        Log.e("LOGON", e.getMessage());
//                    } finally {
//                        PinPadInterface.close();
//                    }
                    return new JSONObject("{\"screen\":{\"ver\":\"1\",\"comps\":{\"comp\":[{\"visible\":true,\"comp_values\":{\"comp_value\":[{\"print\":\"Logon Succesfull\",\n" +
                            "\"value\":\"Logon Succesfull\"}]},\"comp_lbl\":\" \",\"comp_type\":\"1\",\"comp_id\":\"F0003\",\"seq\":0}]},\"id\":\"F000002\",\n" +
                            "\"type\":\"2\",\"title\":\"Sukses\"}}");
                }
                jroot = mlr.loadMenu(context, screenResponse, replyJSON);
            }
            if (replyJSON.has("server_ref")) {
                jroot.put("server_ref",replyJSON.get("server_ref"));
            }
            if (replyJSON.has("server_time")) {
                jroot.put("server_time",replyJSON.get("server_time"));
            }
            if (replyJSON.has("server_date")) {
                jroot.put("server_date",replyJSON.get("server_date"));
            }
            if (serviceid.equals("A25100")||
                    serviceid.equals("A27100")||
                    serviceid.equals("A29200")||
                    serviceid.equals("A2A200")) {
                jroot.put("logid", logId);
            }
            String updRqTime = "";
            if (replyJSON.has("server_time")&&replyJSON.has("server_date")) {
                Date d = new Date();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy");
                String repDate = sdf.format(d) + "-";
                String tmStamp = null;
                repDate = repDate + replyJSON.getString("server_date").substring(0,2) + "-" +
                        replyJSON.getString("server_date").substring(2,4);
                tmStamp = StringLib.toSQLiteTimestamp(repDate, replyJSON.getString("server_time"));
                updRqTime = ", request_time = '"+ tmStamp + "' ";
            }
            msgStatus = "00";
            int prcount = getPrintFromScreen(jroot);
            writeDebugLog("MSGLOG", "update (1475)");
            String uMsgLog = "update messagelog set "
                    + "message_status = '" + msgStatus + "', "
                    + "reply_time = time('now'), "
                    + "response_message = '" + jroot.toString().replaceAll("'","''") + "', "
                    + "print = " + String.valueOf(prcount) + " "
                    + updRqTime
                    + "where log_id = " + logId;
            clientDB.execSQL(uMsgLog);
        }
        writeDebugLog("JSON_RETURN", jroot.toString());
        clientDB.close();
        helperDb.close();
        return jroot;
    }

    public String reverseLastTransaction(Context context) {
        writeDebugLog("REVERSAL", hsToHost);
        Thread doReversal = new Thread(new handleReversal(context, hsToHost, EDCLog, elogid));
        doReversal.start();
        return "ReversalRQ";
    }

    public long insertIntoAidLog(ContentValues contentValues){
        helperDb = new DataBaseHelper(ctx);
        SQLiteDatabase clientDB = null;
        try {
            helperDb.openDataBase();
            clientDB = helperDb.getWritableDatabase();
            return clientDB.insert("brizzi_aid_log", null, contentValues);
        } catch (Exception ex) {
            Log.e("TX", "DB error "+ex.toString());
            return -1;
        }finally {
            clientDB.close();
            helperDb.close();
        }


    }

    public long updateAidById(ContentValues contentValues,long id){
        helperDb = new DataBaseHelper(ctx);
        SQLiteDatabase clientDB = null;
        try {
            helperDb.openDataBase();
            clientDB = helperDb.getWritableDatabase();
            return clientDB.update("brizzi_aid_log", contentValues, "id=" + id, null);
        } catch (Exception ex) {
            Log.e("TX", "DB error");
            return -1;
        }finally {
            clientDB.close();
            helperDb.close();
        }

    }

    public long insertIntoCmdLog(ContentValues contentValues){
        helperDb = new DataBaseHelper(ctx);
        SQLiteDatabase clientDB = null;
        try {
            helperDb.openDataBase();
            clientDB = helperDb.getWritableDatabase();
            return clientDB.insert("brizzi_cmd_log", null, contentValues);
        } catch (Exception ex) {
            Log.e("CMD_LOG", "DB error "+ex.toString());
            return -1;
        }finally {
            clientDB.close();
            helperDb.close();
        }

    }

    private String getIMEI(JSONObject request) throws JSONException {
        String imei = "";
        if (request.has("msg_ui")) {
            imei = (String) request.get("msg_ui");
        } else {
            Log.e("TX", "Parse Request Error : Request contains no client IMEI");
        }
        return imei;
    }

    private String getSTAN(JSONObject request) throws JSONException {
        String stan = "";
        if (request.has("msg_id")) {
            stan = (String) request.get("msg_id");
        } else {
            Log.e("TX", "Parse Request Error : Request contains no message ID");
        }
        return stan;
    }

    private String getData(JSONObject request) throws JSONException {
        String body = "";
        if (request.has("msg_dt")) {
            body = (String) request.get("msg_dt");
        } else {
            Log.e("TX", "Parse Request Error : Request contains no message data");
        }
        return body;
    }

    private String getServiceId(JSONObject request) throws JSONException {
        String serviceId = "";
        if (request.has("msg_si")) {
            serviceId = (String) request.get("msg_si");
        } else {
            Log.e("TX", "Parse Request Error : Request contains no service ID");
        }
        return serviceId;
    }

    private String[] getTransactionElements(JSONObject request) throws JSONException {
        SharedPreferences preferences = ctx.getSharedPreferences(CommonConfig.SETTINGS_FILE, Context.MODE_PRIVATE);
        String[] rqElements = new String[5];
        rqElements[0] = getSTAN(request);
        rqElements[1] = getIMEI(request);
        rqElements[2] = getServiceId(request);
        String[] tmid = new String[2];// = getTerminalMerchantId(rqElements[1]);
        tmid[0] = preferences.getString("terminal_id", CommonConfig.DEV_TERMINAL_ID);//"00000006";
        tmid[1] = preferences.getString("merchant_id", CommonConfig.DEV_MERCHANT_ID);//"000001210000020";
        rqElements[3] = tmid[0];
        rqElements[4] = tmid[1];
        return rqElements;
    }

    public String generateStan() {
        msgSequence++;
        if (msgSequence > 999999) {
            msgSequence = 0;
        }
        return String.format("%06d", msgSequence);
    }

    private int getPrintFromScreen(JSONObject msg) throws JSONException {
        int print;
        JSONObject screen;
        if (msg.has("screen")) {
            screen = msg.getJSONObject("screen");
        } else {
            screen = msg;
        }
        if (screen.has("print")) {
            print = screen.getInt("print");
        } else {
            print = 0;
        }
        return print;
    }

    public byte[] sendMessage(final AsyncMessageWrapper pMessage) {
        int timeout = 30000;
        return sendMessage(pMessage, timeout);
    }

    public byte[] sendMessage(final AsyncMessageWrapper pMessage, int timeout) {
        if (socket_status==IN_USE) {
            return busyResponse;
        }
        Log.i("CONN STS DONE", new SimpleDateFormat("HH:mm:ss").format(new Date()));
        socket_status = IN_USE;
        final InetSocketAddress tGatewaySocketAddress = pMessage.getDestination();
        SharedPreferences preferences = ctx.getSharedPreferences(CommonConfig.SETTINGS_FILE, Context.MODE_PRIVATE);
        String host_ip = preferences.getString("ip", CommonConfig.DEV_SOCKET_IP);
        int host_port = Integer.valueOf(preferences.getString("port", CommonConfig.DEV_SOCKET_PORT));
        String tRequestStream = pMessage.getMessageStream();
        Log.i("GET MSG DONE", new SimpleDateFormat("HH:mm:ss").format(new Date()));
        byte[] respBytes = new byte[0];
        Log.i("CR8SOC START", new SimpleDateFormat("HH:mm:ss").format(new Date()));
        Socket tGatewaySocket = new Socket();
        Log.i("CR8SOC DONE", new SimpleDateFormat("HH:mm:ss").format(new Date()));
        byte[] message = ISO8583Parser.hexStringToByteArray(tRequestStream);
//        logTrace("Connecting to " + tGatewaySocketAddress.getHostName() + ":" + tGatewaySocketAddress.getPort());
        logTrace("Connecting to " + host_ip + ":" + host_port);
        Log.i("CONN AT", new SimpleDateFormat("HH:mm:ss").format(new Date()));
        final InetSocketAddress inetSocketAddress = new InetSocketAddress(host_ip, host_port);

        try {
            tGatewaySocket.setSoTimeout(timeout);
//            tGatewaySocket.connect(tGatewaySocketAddress, timeout);
            tGatewaySocket.connect(inetSocketAddress, timeout);

        } catch (IOException ex) {
            logTrace("IOException on SendMessage() when connecting to Gateway at " + tGatewaySocketAddress);
            socket_status = IDLE;
            return null;
        }

//        logTrace("Connected to " + tGatewaySocketAddress.getHostName() + ":" + tGatewaySocketAddress.getPort());
        logTrace("Connected to " + host_ip + ":" + host_port);
        ByteArrayOutputStream tRequestByteStream = new ByteArrayOutputStream();

        try {

//            tRequestByteStream.write(cEndMessageByte);
            tRequestByteStream.write(message);
        } catch (IOException ex) {
            logTrace("IOException on SendMessage() when writing request stream " + tRequestStream + " to byte array output stream.");
            socket_status = IDLE;
            return null;
        }

        logTrace("Request : [" + tRequestStream + "]");

        try {
            tGatewaySocket.getOutputStream().write(message);
        } catch (IOException ex) {
            logTrace("IOException on SendMessage() when writing stream " + tRequestStream + " + to outgoing socket at " + tGatewaySocketAddress);
            socket_status = IDLE;
            return null;
        }
        try {
            MyWrapper pbi = new MyWrapper(tGatewaySocket.getInputStream());
            DataInputStream reader = new DataInputStream(pbi);

            int available = reader.available();
            int tMessageByte = -1;
            respBytes = new byte[available];
            for (int i = 0; i < available; i++) {
                tMessageByte = reader.read();
                respBytes[i] = (byte) tMessageByte;
            }

            String s = new String(respBytes);
            s = ISO8583Parser.bytesToHex(respBytes);
            logTrace("Response : [" + s + "]");
            reader.close();
        } catch (IOException ex) {
            System.err.println(ex);
            socket_status = IDLE;
            return null;
        }
//        logTrace("Disconnected from " + tGatewaySocketAddress.getHostName() + ":" + tGatewaySocketAddress.getPort());
        logTrace("Disconnected from " + host_ip + ":" + host_port);
        socket_status = IDLE;
        return respBytes;
    }




    class MyWrapper extends PushbackInputStream {

        MyWrapper(InputStream in) {
            super(in);
        }

        @Override
        public int available() throws IOException {
            int b = super.read();
            super.unread(b);
            return super.available();
        }
    }

    class handleReversal implements Runnable {
        private Context context;
        private String oriMsg;
        private LogHandler EDCLog;
        private int elogid;

        public handleReversal(Context context, String oriMsg, LogHandler EDCLog, int elogid) {
            this.context = context;
            this.oriMsg = oriMsg;
            this.EDCLog = EDCLog;
            this.elogid = elogid;
        }

        @Override
        public void run() {
            sendReversal();
        }

        private String reversalMessage(String originalMessage) {
            String isoBitlength = "0000";
            String isoHeader = "6000070000";
            String isoMti = "0400";
            return originalMessage.substring(0, isoBitlength.length()+isoHeader.length())
                    + isoMti + originalMessage.substring(isoBitlength.length()
                    +isoHeader.length()+isoMti.length());
        }

        private void sendReversal() {
            String revMsgToHost = reversalMessage(oriMsg);
            SharedPreferences preferences = ctx.getSharedPreferences(CommonConfig.SETTINGS_FILE, Context.MODE_PRIVATE);
            String host_ip = preferences.getString("ip", CommonConfig.DEV_SOCKET_IP);
            int host_port = Integer.valueOf(preferences.getString("port", CommonConfig.DEV_SOCKET_PORT));
            AsyncMessageWrapper amw = new AsyncMessageWrapper(host_ip, host_port, revMsgToHost);
            byte[] revResponse = sendMessage(amw);
            if (revResponse == null) {
                //reversal no response
                EDCLog.writeRevResponse("PE", oriMsg, elogid);
                return;
            } else {
                if (revResponse==busyResponse) {
                    return;
                }
                ISO8583Parser rpParser = new ISO8583Parser(context, "6000070000", ISO8583Parser.bytesToHex(revResponse), 2);
                EDCLog.writePostLog(rpParser.getIsoBitValue(), elogid);
                String[] replyValues = rpParser.getIsoBitValue();
                if (replyValues[39] != null) {
                    EDCLog.writeRevResponse(replyValues[39], oriMsg, elogid);
                    return;
                }
                EDCLog.writeRevResponse("PE", oriMsg, elogid);
            }
        }
    }

    public List<PrintSize> getPrintData() {
        return printData;
    }

    public boolean isHasPrintData() {
        return hasPrintData;
    }

    public void setPrintData(List<PrintSize> printData) {
        this.printData = printData;
    }

    public void setHasPrintData(boolean hasPrintData) {
        this.hasPrintData = hasPrintData;
    }

    public void setPrintText(String printText) {
        this.printText = printText;
    }

    public String getPrintText() {
        return printText;
    }
    
    public void writeDebugLog(String category, String msg) {
        if (DEBUG_LOG) {
            Date d = new Date();
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm:ss");
            Log.d("DEBUG", "[" + sdf.format(d) + "] " + category + " - " + msg);
        }
    }
}

