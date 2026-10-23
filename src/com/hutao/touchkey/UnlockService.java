package com.hutao.touchkey;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.widget.Toast;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** A widget click connects directly; it never launches an activity. */
public final class UnlockService extends Service {
    private static final String CHANNEL="widget-unlock";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private final AtomicBoolean ownsGate=new AtomicBoolean(false);
    private volatile LockEngine engine;
    private boolean busy;
    private int latestStartId;

    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        latestStartId=startId;
        if(busy){toast("正在开锁，请稍候");return START_NOT_STICKY;}
        try{
            NotificationManager manager=getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,"桌面开锁状态",NotificationManager.IMPORTANCE_LOW));
            Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(ht.key.R.drawable.app_icon)
                .setContentTitle("正在连接门锁").setContentText("请保持手机靠近门锁")
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build();
            if(Build.VERSION.SDK_INT>=29)startForeground(19,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(19,notification);
            if(!UnlockGate.OPENING.compareAndSet(false,true)){
                toast("正在开锁，请稍候");stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);return START_NOT_STICKY;
            }
            ownsGate.set(true);busy=true;
            UnlockWidget.status(this,"正在连接，请保持靠近");
            work.execute(()->unlock(startId));
        }catch(Exception e){
            releaseGate();UnlockWidget.status(this,"无法开始，请打开应用检查");toast("无法开始开锁，请打开胡桃碰一碰检查权限");
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);
        }
        return START_NOT_STICKY;
    }
    private void unlock(int startId){
        try{
            if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)
                throw new Exception("请打开胡桃碰一碰，授予附近设备权限");
            SharedPreferences prefs=getSharedPreferences("settings",0);
            String mac=prefs.getString("mac","");if(mac.isEmpty()||!KeyStore.has(this))throw new Exception("请先在胡桃碰一碰中导入门锁密钥");
            LockEngine current=new LockEngine();engine=current;
            current.run(this,mac,KeyStore.read(this),true,message->{});
            prefs.edit().putBoolean("validated",true).apply();
            Vibrator vibrator=getSystemService(Vibrator.class);if(vibrator!=null)vibrator.vibrate(VibrationEffect.createOneShot(80,VibrationEffect.DEFAULT_AMPLITUDE));
            result(true,"门锁已确认开锁成功");UnlockWidget.status(this,"已开门 · 点击可再次开门");toast("开锁成功");
        }catch(Exception e){
            String message=e.getMessage()==null?"开锁未完成，请重试":e.getMessage();
            result(false,message);UnlockWidget.status(this,"未确认开门 · 靠近后重试");toast(message);
        }finally{
            engine=null;releaseGate();ui.post(()->{busy=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(latestStartId);});
        }
    }
    private void result(boolean success,String message){
        try{
            JSONObject value=new JSONObject().put("ok",success).put("message",message).put("source","widget").put("timestamp",System.currentTimeMillis());
            try(FileOutputStream file=openFileOutput("last-result.json",MODE_PRIVATE)){file.write(value.toString().getBytes(StandardCharsets.UTF_8));}
        }catch(Exception ignored){}
    }
    private void toast(String message){ui.post(()->Toast.makeText(this,message,Toast.LENGTH_LONG).show());}
    private void releaseGate(){if(ownsGate.getAndSet(false))UnlockGate.OPENING.set(false);}
    @Override public void onDestroy(){LockEngine current=engine;if(current!=null)current.close();work.shutdownNow();releaseGate();super.onDestroy();}
}
