package com.hutao.touchkey;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.nfc.*;
import android.nfc.tech.Ndef;
import android.os.*;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** User-enabled listener; the phone remains locked throughout BLE door authentication. */
public final class LockScreenService extends Service {
    static volatile boolean RUNNING;
    static final String STOP="ht.key.STOP_LOCKED_NFC";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean ownsGate=new AtomicBoolean();
    private SharedPreferences prefs;
    private LockedNfc listener;
    private volatile LockEngine engine;
    private boolean active;
    private long lastTap;
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){super.onCreate();prefs=getSharedPreferences("settings",0);
        try{
            NotificationManager manager=getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("locked-nfc","锁屏碰一碰监听",NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop=PendingIntent.getService(this,41,new Intent(this,LockScreenService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent show=PendingIntent.getActivity(this,42,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n=new Notification.Builder(this,"locked-nfc").setSmallIcon(ht.key.R.drawable.app_icon)
                .setContentTitle("锁屏碰一碰已开启").setContentText("亮屏且未解锁时，碰已登记的门锁标签")
                .setContentIntent(show).addAction(new Notification.Action.Builder(null,"关闭监听",stop).build())
                .setOngoing(true).setOnlyAlertOnce(true).build();
            startForeground(41,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            if(checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)!=PackageManager.PERMISSION_GRANTED)throw new Exception("尚未获得电脑授权，请先授予锁屏 NFC 权限");
            if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)throw new Exception("请允许通知后再开启锁屏监听");
            if(!ready())throw new Exception("请先完成标签配置、开锁验证，并开启碰一碰自动开锁");
            listener=new LockedNfc(this,this::onTag);listener.register();active=true;RUNNING=true;
            record("亮屏锁屏监听运行中；息屏暂不支持");
        }catch(Exception e){record("监听未开启："+message(e));stopSelf();}
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){prefs.edit().putBoolean("lockedNfc",false).commit();stopSelf();return START_NOT_STICKY;}
        return active&&ready()?START_STICKY:START_NOT_STICKY;
    }
    @Override public void onTaskRemoved(Intent rootIntent){
        if(active&&ready())prefs.edit().putLong("listenerTaskRemovedAt",System.currentTimeMillis()).apply();
        super.onTaskRemoved(rootIntent);
    }
    private boolean ready(){return prefs.getBoolean("lockedNfc",false)&&prefs.getBoolean("auto",false)&&prefs.getBoolean("validated",false)&&prefs.getBoolean("configured",false)&&!prefs.getString("tagId","").isEmpty()&&KeyStore.has(this);}
    private void onTag(Tag tag){
        if(!active||!getSystemService(KeyguardManager.class).isKeyguardLocked()||!getSystemService(PowerManager.class).isInteractive()||!ready())return;
        if(!prefs.getString("tagId","").equals(LockProtocol.hex(tag.getId())))return;
        Ndef ndef=Ndef.get(tag);NdefMessage msg=ndef==null?null:ndef.getCachedNdefMessage();
        if(!matches(msg))return;
        synchronized(this){long now=SystemClock.elapsedRealtime();
            if((lastTap!=0&&now-lastTap<8000)||System.currentTimeMillis()-prefs.getLong("lastNfcAt",0)<8000||!UnlockGate.OPENING.compareAndSet(false,true))return;
            ownsGate.set(true);lastTap=now;prefs.edit().putLong("lastNfcAt",System.currentTimeMillis()).apply();
            try{worker.execute(this::unlock);}catch(RejectedExecutionException e){releaseGate();}
        }
    }
    private boolean matches(NdefMessage message){
        if(message==null)return false;
        for(NdefRecord r:message.getRecords()){Uri uri=r.toUri();
            if(uri!=null&&"https".equals(uri.getScheme())&&"biz.confong.cn".equals(uri.getHost())&&"/nfc/lock".equals(uri.getPath())){
                String mac=uri.getQueryParameter("mac");
                if(mac!=null&&mac.replace(":","").equalsIgnoreCase(prefs.getString("mac","").replace(":","")))return true;
            }
        }return false;
    }
    private void unlock(){try{
        if(!active||!ready())return;
        LockEngine current=new LockEngine();engine=current;record("锁屏标签已匹配，正在连接门锁");
        if(!active){current.close();return;}
        current.run(this,prefs.getString("mac",""),KeyStore.read(this),true,value->{});
        prefs.edit().putBoolean("validated",true).apply();result(true,"门锁已确认开锁成功");
        UnlockWidget.status(this,"已开门 · 点击可再次开门");record("锁屏开锁成功，门锁已回执确认");
        getSystemService(Vibrator.class).vibrate(VibrationEffect.createOneShot(80,VibrationEffect.DEFAULT_AMPLITUDE));
    }catch(Exception e){result(false,message(e));record("锁屏开锁未完成："+message(e));}
    finally{engine=null;releaseGate();}}
    private void result(boolean ok,String text){try{
        JSONObject value=new JSONObject().put("ok",ok).put("message",text).put("source","locked-nfc").put("lockedAtTap",true).put("interactiveAtTap",true).put("timestamp",System.currentTimeMillis());
        try(FileOutputStream out=openFileOutput("last-result.json",MODE_PRIVATE)){out.write(value.toString().getBytes(StandardCharsets.UTF_8));}
    }catch(Exception ignored){}}
    private void record(String text){prefs.edit().putString("lockedNfcStatus",text).putLong("lockedNfcStatusAt",System.currentTimeMillis()).apply();}
    private String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private void releaseGate(){if(ownsGate.getAndSet(false))UnlockGate.OPENING.set(false);}
    @Override public void onDestroy(){active=false;RUNNING=false;if(listener!=null)try{listener.unregister();}catch(Exception e){record("撤销监听失败，请重启手机后再试");}
        LockEngine current=engine;if(current!=null)current.close();worker.shutdownNow();releaseGate();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
