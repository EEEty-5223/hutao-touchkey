package com.hutao.touchkey;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;
import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener;
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Opt-in authenticated Xiaomi app channel. Only the configured door is used. */
public final class WristService extends Service {
    static final String STOP="ht.key.STOP_WRIST";
    static volatile boolean RUNNING;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private final AtomicBoolean ownsGate=new AtomicBoolean();
    private final WristSession session=new WristSession();
    private volatile LockEngine engine;
    private SharedPreferences prefs;
    private String nodeId;
    private volatile boolean active;
    private boolean registered,pending;
    private long generation;
    private final OnMessageReceivedListener receiver=(node,bytes)->{
        if(bytes!=null&&bytes.length<=1024){byte[] copy=bytes.clone();main.post(()->receive(node,copy));}
    };
    private final Runnable retry=new Runnable(){@Override public void run(){if(active){register();main.postDelayed(this,15000);}}};
    private final OnServiceConnectionListener connection=new OnServiceConnectionListener(){
        @Override public void onServiceConnected(){main.post(()->{register();});}
        @Override public void onServiceDisconnected(){main.post(()->{registered=false;pending=false;generation++;session.clear();record("通信已断开，等待恢复");});}
    };
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){
        super.onCreate();prefs=getSharedPreferences("wrist",0);nodeId=prefs.getString("node","");
        try{
            NotificationManager nm=getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("wrist-control","手环开门通信",NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop=PendingIntent.getService(this,51,new Intent(this,WristService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent show=PendingIntent.getActivity(this,52,new Intent(this,WristActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n=new Notification.Builder(this,"wrist-control").setSmallIcon(ht.key.R.drawable.app_icon).setContentTitle("手环开门通信已开启")
                .setContentText("手环点开门后，由这台手机连接门锁").setContentIntent(show).setOngoing(true)
                .addAction(new Notification.Action.Builder(null,"关闭手环通信",stop).build()).build();
            if(Build.VERSION.SDK_INT>=29)startForeground(51,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(51,n);
            if(!prefs.getBoolean("enabled",false)||nodeId.isEmpty())throw new Exception("请先完成手环通信授权");
            if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)throw new Exception("请允许附近设备权限");
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)throw new Exception("请允许通知");
            active=true;RUNNING=true;
            Wearable.getServiceApi(this).registerServiceConnectionListener(connection);main.post(retry);
        }catch(Exception e){record("通信未开启："+message(e));stopSelf();}
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&STOP.equals(intent.getAction())){prefs.edit().putBoolean("enabled",false).commit();stopSelf();return START_NOT_STICKY;}
        return active&&prefs.getBoolean("enabled",false)?START_STICKY:START_NOT_STICKY;
    }
    private void register(){
        if(!active||registered||pending)return;pending=true;long attempt=++generation;
        main.postDelayed(()->{if(active&&attempt==generation&&pending){pending=false;generation++;record("等待小米通信服务响应，稍后重试");}},12000);
        Wearable.getMessageApi(this).removeListener(nodeId).addOnSuccessListener(v->main.post(()->addReceiver(attempt)))
            .addOnFailureListener(e->main.post(()->{
                if("you have not registered".equals(e.getMessage()))addReceiver(attempt);
                else if(active&&attempt==generation){pending=false;record("重置手环监听失败："+message(e));}
            }));
    }
    private void addReceiver(long attempt){
        if(!active||attempt!=generation)return;
        Wearable.getMessageApi(this).addListener(nodeId,receiver).addOnSuccessListener(v->main.post(()->{
            if(!active||attempt!=generation)return;pending=false;registered=true;record("手环通信已就绪，请在手环上检查连接");
        })).addOnFailureListener(e->main.post(()->{if(!active||attempt!=generation)return;pending=false;record("等待手环连接："+message(e));}));
    }
    private void receive(String source,byte[] bytes){
        if(!active||!prefs.getBoolean("enabled",false)||!nodeId.equals(source))return;
        try{
            JSONObject request=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            if(request.optInt("v")!=1)return;
            String id=request.optString("id");if(!id.matches("[A-Za-z0-9_-]{1,64}"))return;
            String type=request.optString("type");
            if("ping".equals(type)){
                String challenge=session.issue(SystemClock.elapsedRealtime());
                send(new JSONObject().put("v",1).put("type","pong").put("id",id).put("token",challenge));record("已收到手环检查，手机已响应");return;
            }
            if(!"unlock".equals(type))return;
            if(!session.consume(request.optString("token"),SystemClock.elapsedRealtime())){reply(id,false,"请求已过期或过于频繁，请重新点击");return;}
            SharedPreferences settings=getSharedPreferences("settings",0);
            if(!settings.getBoolean("validated",false)||settings.getString("mac","").isEmpty()||!KeyStore.has(this)){reply(id,false,"请先在手机上完成钥匙验证");return;}
            if(!UnlockGate.OPENING.compareAndSet(false,true)){reply(id,false,"正在开门，请稍候");return;}
            ownsGate.set(true);record("已收到手环开门请求，正在连接门锁");
            try{work.execute(()->unlock(id));}catch(RejectedExecutionException e){releaseGate();reply(id,false,"通信已关闭");}
        }catch(Exception ignored){record("已忽略格式不正确的手环消息");}
    }
    private void unlock(String id){
        try{
            if(!active||!prefs.getBoolean("enabled",false))return;
            LockEngine current=new LockEngine();engine=current;
            if(!active){current.close();return;}
            SharedPreferences settings=getSharedPreferences("settings",0);
            current.run(this,settings.getString("mac",""),KeyStore.read(this),true,m->{});
            main.post(()->{if(active){reply(id,true,"门锁已确认开门");UnlockWidget.status(this,"已开门 · 点击可再次开门");record("手环开门成功，门锁已回执确认");}});
        }catch(Exception e){main.post(()->{if(active){reply(id,false,message(e));record("手环开门未完成："+message(e));}});}
        finally{engine=null;releaseGate();}
    }
    private void reply(String id,boolean ok,String text){try{send(new JSONObject().put("v",1).put("type","result").put("id",id).put("ok",ok).put("message",text));}catch(Exception ignored){}}
    private void send(JSONObject message){if(active)Wearable.getMessageApi(this).sendMessage(nodeId,message.toString().getBytes(StandardCharsets.UTF_8)).addOnFailureListener(e->record("发送手环响应失败："+message(e)));}
    private String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private void record(String value){if(prefs!=null)prefs.edit().putString("status",value).putLong("statusAt",System.currentTimeMillis()).apply();}
    private void releaseGate(){if(ownsGate.getAndSet(false))UnlockGate.OPENING.set(false);}
    @Override public void onDestroy(){active=false;RUNNING=false;generation++;session.clear();main.removeCallbacksAndMessages(null);
        LockEngine current=engine;if(current!=null)current.close();work.shutdownNow();releaseGate();
        try{Wearable.getServiceApi(this).unregisterServiceConnectionListener(connection);Wearable.getMessageApi(this).removeListener(nodeId);}catch(Exception ignored){}
        super.onDestroy();}
}
