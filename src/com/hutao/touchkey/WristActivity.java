package com.hutao.touchkey;

import android.app.Activity;
import android.os.*;
import android.graphics.Color;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.widget.*;
import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.node.Node;
import com.xiaomi.xms.wearable.auth.Permission;
import java.util.List;

/** Explicit, user-driven connection setup. No door command is sent here. */
public final class WristActivity extends Activity {
    private TextView status;
    private String nodeId="";
    private boolean authorized;
    private TextView serviceStatus;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){@Override public void run(){if(serviceStatus!=null)serviceStatus.setText((WristService.RUNNING?"手环通信运行中\n":"手环通信已关闭\n")+getSharedPreferences("wrist",0).getString("status",""));main.postDelayed(this,1000);}};
    private final Runnable timeout=()->show("连接检查超时，请确认小米运动健康已打开且手环已连接");
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(32,70,32,32);root.setBackgroundColor(Color.rgb(15,20,31));
        TextView title=new TextView(this);title.setText("手环开门 · 连接检查");title.setTextSize(24);title.setTextColor(Color.WHITE);root.addView(title);
        status=new TextView(this);status.setTextSize(17);status.setTextColor(Color.WHITE);status.setPadding(0,30,0,30);root.addView(status);
        Button check=new Button(this);check.setText("检查手环连接（不开门）");root.addView(check);check.setOnClickListener(v->scan());
        Button auth=new Button(this);auth.setText("授权手环通信（不开门）");root.addView(auth);auth.setOnClickListener(v->authorize());
        Button start=new Button(this);start.setText("开启手环开门通信");root.addView(start);start.setOnClickListener(v->startCommunication());
        Button stop=new Button(this);stop.setText("关闭手环开门通信");root.addView(stop);stop.setOnClickListener(v->{getSharedPreferences("wrist",0).edit().putBoolean("enabled",false).commit();stopService(new Intent(this,WristService.class));});
        serviceStatus=new TextView(this);serviceStatus.setTextColor(Color.LTGRAY);serviceStatus.setPadding(0,20,0,20);root.addView(serviceStatus);
        TextView note=new TextView(this);note.setText("先安装配套手环小应用。检查连接不会开门；开启通信后，仅在手环点“开门”才连接门锁。钥匙只留在手机。手机需在门锁附近。");note.setTextColor(Color.LTGRAY);root.addView(note);
        setContentView(root);scan();main.post(refresh);
    }
    private void scan(){
        nodeId="";authorized=false;show("正在检查小米运动健康通信服务…");main.removeCallbacks(timeout);main.postDelayed(timeout,15000);
        try{Wearable.getNodeApi(this).getConnectedNodes().addOnSuccessListener(this::nodes).addOnFailureListener(this::failure);}
        catch(Exception e){failure(e);}
    }
    private void nodes(List<Node> nodes){
        main.removeCallbacks(timeout);
        if(nodes==null||nodes.isEmpty()){show("没有已连接的手环，请先在小米运动健康中连接");return;}
        if(nodes.size()!=1){show("检测到多个设备，请先只连接要测试的手环");return;}
        Node n=nodes.get(0);nodeId=n.id;
        show("已识别："+n.name+"\n正在查询通信授权…");
        Wearable.getAuthApi(this).checkPermission(nodeId,Permission.DEVICE_MANAGER)
            .addOnSuccessListener(ok->{authorized=ok;if(ok)getSharedPreferences("wrist",0).edit().putString("node",nodeId).apply();show("已识别："+n.name+"\n通信权限："+(ok?"已授权，可开启通信":"未授权，请点授权按钮"));})
            .addOnFailureListener(this::failure);
    }
    private void authorize(){
        if(nodeId.isEmpty()){scan();return;}
        show("正在申请手环通信授权，请查看系统提示…");
        try{Wearable.getAuthApi(this).requestPermission(nodeId,Permission.DEVICE_MANAGER)
            .addOnSuccessListener(p->{show("通信授权已返回，正在复查…");scan();}).addOnFailureListener(this::failure);}
        catch(Exception e){failure(e);}
    }
    private void startCommunication(){
        if(!authorized||nodeId.isEmpty()){show("请先完成连接检查与手环通信授权");return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},61);return;}
        getSharedPreferences("wrist",0).edit().putBoolean("enabled",true).putString("node",nodeId).commit();
        try{startForegroundService(new Intent(this,WristService.class));}catch(RuntimeException e){getSharedPreferences("wrist",0).edit().putBoolean("enabled",false).apply();show("未能开启通信，请重新打开应用");}
    }
    private void failure(Exception e){main.removeCallbacks(timeout);show(e instanceof com.xiaomi.xms.wearable.exception.AppNotInstalledException?
        "请先在手环安装同包名、同签名的“胡桃开门”测试小应用，再检查连接。":
        "通信检查失败："+e.getClass().getSimpleName()+"\n"+(e.getMessage()==null?"请重新检查连接":e.getMessage()));}
    private void show(String text){main.post(()->{if(isDestroyed())return;status.setText(text);getSharedPreferences("wrist",0).edit().putString("diagnostic",text).apply();
        android.util.Log.i("HutaoWrist",text);});}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);super.onDestroy();}
}
