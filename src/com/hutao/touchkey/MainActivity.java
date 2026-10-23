package com.hutao.touchkey;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.nfc.*;
import android.nfc.tech.Ndef;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {
    private final int bg=Color.rgb(15,20,31),fg=Color.rgb(238,242,250),muted=Color.rgb(163,177,202);
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private final AtomicBoolean handling=new AtomicBoolean(false);
    private final AtomicBoolean opening=UnlockGate.OPENING;
    private NfcAdapter adapter;
    private SharedPreferences prefs;
    private TextView status,summary,details;
    private Switch auto;
    private Switch lockedNfc;
    private TextView lockedStatus;
    private boolean updatingLockedUi;
    private Button open,writeTag;
    private volatile int mode;
    private volatile LockEngine engine;
    private long lastTap;
    private boolean nfcLaunch;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);prefs=getSharedPreferences("settings",0);consumeSetup();
        adapter=NfcAdapter.getDefaultAdapter(this);buildUi();writePlan();UnlockWidget.refreshAll(this);handleIntent(getIntent());
    }
    private void buildUi(){
        getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(bg);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(34),dp(24),dp(28));scroll.addView(root);
        root.addView(text("胡桃碰一碰",29,fg,true));
        TextView sub=text("属于你的门锁，一碰就到家",15,muted,false);sub.setPadding(0,dp(9),0,dp(24));root.addView(sub);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(22),dp(22),dp(22),dp(22));card.setBackground(round(Color.rgb(26,34,50)));root.addView(card);
        status=text("准备就绪",23,fg,true);card.addView(status);
        summary=text("",15,muted,false);summary.setPadding(0,dp(15),0,0);card.addView(summary);
        open=button("手动开锁",Color.rgb(225,73,128));root.addView(open);open.setOnClickListener(v->startUnlock(true));
        Button widget=button("添加桌面开锁卡片",Color.rgb(49,61,85));root.addView(widget);widget.setOnClickListener(v->addWidget());
        Button probe=button("验证钥匙（不开锁）",Color.rgb(49,61,85));root.addView(probe);probe.setOnClickListener(v->startUnlock(false));
        Button wrist=button("手环开门（测试）",Color.rgb(49,61,85));root.addView(wrist);wrist.setOnClickListener(v->startActivity(new Intent(this,WristActivity.class)));
        auto=new Switch(this);auto.setText("碰一碰自动开锁");auto.setTextSize(17);auto.setTextColor(fg);auto.setPadding(0,dp(23),0,dp(12));auto.setChecked(prefs.getBoolean("auto",false));root.addView(auto);
        auto.setOnCheckedChangeListener((view,checked)->{
            if(checked&&!prefs.getBoolean("validated",false)){view.setChecked(false);show("请先完成一次手动开锁验证");return;}
            prefs.edit().putBoolean("auto",checked).apply();
            if(!checked){prefs.edit().putBoolean("lockedNfc",false).apply();stopService(new Intent(this,LockScreenService.class));}
            refresh();
        });
        lockedNfc=new Switch(this);lockedNfc.setText("锁屏碰一碰（实验：需亮屏）");lockedNfc.setTextSize(17);lockedNfc.setTextColor(fg);root.addView(lockedNfc);
        lockedStatus=text("",13,muted,false);root.addView(lockedStatus);
        TextView lockedNote=text("保持后台监听通知，允许自启动和后台运行。开机首次解锁后尝试恢复；系统强行停止后需重新打开应用。",13,muted,false);lockedNote.setPadding(0,dp(8),0,dp(12));root.addView(lockedNote);
        lockedNfc.setOnCheckedChangeListener((view,checked)->{
            if(updatingLockedUi)return;
            if(checked){
                String error=!LockedNfc.supported()?"这台手机的系统版本尚未验证锁屏 NFC":
                    checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)!=PackageManager.PERMISSION_GRANTED?"请先通过电脑授予锁屏 NFC 权限（系统安全设置权限）":
                    !hasBluetooth()?"请先授予附近设备权限":
                    !prefs.getBoolean("auto",false)||!prefs.getBoolean("configured",false)||!prefs.getBoolean("validated",false)||prefs.getString("tagId","").isEmpty()?"请先完成标签配置、开锁验证，并开启碰一碰自动开锁":null;
                if(error!=null){updatingLockedUi=true;view.setChecked(false);updatingLockedUi=false;show(error);return;}
                if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                    updatingLockedUi=true;view.setChecked(false);updatingLockedUi=false;
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},25);return;
                }
                prefs.edit().putBoolean("lockedNfc",true).apply();
                try{startForegroundService(new Intent(this,LockScreenService.class));}
                catch(Exception e){prefs.edit().putBoolean("lockedNfc",false).apply();show("无法启动锁屏监听，请重新打开应用");}
            }else{prefs.edit().putBoolean("lockedNfc",false).apply();stopService(new Intent(this,LockScreenService.class));}
            view.postDelayed(this::refresh,800);
        });
        Switch hideRecent=new Switch(this);hideRecent.setText("隐藏最近任务卡片");hideRecent.setTextSize(17);hideRecent.setTextColor(fg);
        hideRecent.setChecked(prefs.getBoolean("hideRecents",true));root.addView(hideRecent);
        TextView hideNote=text("保留桌面图标和监听通知，减少误划掉后台卡片。关闭此开关可恢复卡片显示。",13,muted,false);hideNote.setPadding(0,dp(8),0,dp(12));root.addView(hideNote);
        hideRecent.setOnCheckedChangeListener((view,checked)->{prefs.edit().putBoolean("hideRecents",checked).apply();applyRecentVisibility();});
        Button importKey=button("导入门锁密钥",Color.rgb(49,61,85));root.addView(importKey);importKey.setOnClickListener(v->importKey());
        Button learn=button("读取门锁标签",Color.rgb(49,61,85));root.addView(learn);learn.setOnClickListener(v->{mode=0;nfcLaunch=false;enableReader();show("请保持本页打开，靠近门锁标签");});
        writeTag=button("配置原标签，启用碰一碰",Color.rgb(49,61,85));root.addView(writeTag);writeTag.setOnClickListener(v->confirmWrite(false));
        Button restore=button("恢复标签原来的内容",Color.rgb(37,46,64));root.addView(restore);restore.setOnClickListener(v->confirmWrite(true));
        Button nfc=button("NFC 与蓝牙权限",Color.rgb(37,46,64));root.addView(nfc);nfc.setOnClickListener(v->{if(!hasBluetooth())requestBluetooth();else startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));});
        details=text("",13,muted,false);details.setTextIsSelectable(true);details.setPadding(0,dp(24),0,0);root.addView(details);
        setContentView(scroll);refresh();
    }
    private void refresh(){
        String mac=prefs.getString("mac","");summary.setText(mac.isEmpty()?"尚未配置钥匙":"门锁 "+mac+"\n"+(KeyStore.has(this)?"密钥已加密保存在本机":"尚未导入密钥")+"\n"+(prefs.getBoolean("validated",false)?"已通过本机开锁验证":"等待本机开锁验证"));
        if(details!=null)details.setText(prefs.getString("report",""));
        if(lockedNfc!=null){updatingLockedUi=true;lockedNfc.setChecked(LockScreenService.RUNNING&&prefs.getBoolean("lockedNfc",false));updatingLockedUi=false;
            lockedStatus.setText((LockScreenService.RUNNING?"监听运行中":"监听未运行")+"\n最近记录："+prefs.getString("lockedNfcStatus","开启后，点亮屏幕但保持锁定，再碰门锁标签。"));}
    }
    private void show(String value){runOnUiThread(()->{if(!isFinishing())status.setText(value);});}
    private void enableReader(){if(!nfcLaunch&&adapter!=null&&adapter.isEnabled())adapter.enableReaderMode(this,this,NfcAdapter.FLAG_READER_NFC_A|NfcAdapter.FLAG_READER_NFC_B|NfcAdapter.FLAG_READER_NFC_F|NfcAdapter.FLAG_READER_NFC_V,null);}
    private void applyRecentVisibility(){
        boolean hide=prefs.getBoolean("hideRecents",true);
        try{android.app.ActivityManager manager=getSystemService(android.app.ActivityManager.class);
            for(android.app.ActivityManager.AppTask task:manager.getAppTasks()){
                android.app.ActivityManager.RecentTaskInfo info=task.getTaskInfo();
                int id=Build.VERSION.SDK_INT>=29?info.taskId:info.id;
                if(id==getTaskId())task.setExcludeFromRecents(hide);
            }
        }catch(RuntimeException e){show("未能更新后台卡片显示，请重新打开应用");}
    }
    @Override protected void onResume(){super.onResume();applyRecentVisibility();enableReader();ListenerRestoreReceiver.restore(this);refresh();if(lockedNfc!=null)lockedNfc.postDelayed(this::refresh,800);}
    @Override protected void onPause(){mode=0;if(adapter!=null)adapter.disableReaderMode(this);super.onPause();}
    @Override protected void onDestroy(){LockEngine e=engine;if(e!=null)e.close();work.shutdownNow();super.onDestroy();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent);}
    private void handleIntent(Intent intent){
        if(intent==null||!NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction()))return;
        nfcLaunch=true;
        Tag tag=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(NfcAdapter.EXTRA_TAG,Tag.class):intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if(tag==null){show("没有有效的 NFC 标签，请在门锁旁碰一碰");return;}
        onTagDiscovered(tag);
    }
    @Override public void onTagDiscovered(Tag tag){
        NfcAudio.onTag(this,tag);
        if(opening.get()||!handling.compareAndSet(false,true))return;
        if(work.isShutdown()){handling.set(false);return;}
        final int requestedMode=mode;
        work.execute(()->{
            try{
                Ndef ndef=Ndef.get(tag);if(ndef==null)throw new Exception("标签不是可识别的门锁标签");
                ndef.connect();
                try{
                    NdefMessage current=requestedMode==2?null:ndef.getNdefMessage();
                    if(requestedMode!=0){write(ndef,tag,current,requestedMode==2);return;}
                    String mac=macFrom(current),configured=prefs.getString("mac","");
                    if(mac.isEmpty())throw new Exception("这个标签不包含支持的门锁信息");
                    if(configured.isEmpty()||!configured.equals(mac))throw new Exception("这不是已配置的门锁");
                    String id=LockProtocol.hex(tag.getId()),known=prefs.getString("tagId","");
                    if(!known.isEmpty()&&!known.equals(id))throw new Exception("标签编号与已登记的门锁不一致");
                    NfcAudio.play(this);
                    SharedPreferences.Editor edit=prefs.edit().putString("tagId",id);
                    if(prefs.getString("original","").isEmpty()&&!hasOwnRecord(current))edit.putString("original",LockProtocol.hex(current.toByteArray()));
                    String report="已识别门锁标签\n"+mac+"\n标签编号 "+id+"\n标签容量 "+ndef.getMaxSize()+" 字节\n当前内容 "+current.toByteArray().length+" 字节";
                    edit.putString("report",report).apply();runOnUiThread(this::refresh);
                    if(prefs.getBoolean("auto",false)&&prefs.getBoolean("validated",false)){
                        long now=SystemClock.elapsedRealtime();if(now-lastTap<8000||System.currentTimeMillis()-prefs.getLong("lastNfcAt",0)<8000)return;lastTap=now;prefs.edit().putLong("lastNfcAt",System.currentTimeMillis()).apply();runOnUiThread(()->startUnlock(true));
                    }else show("标签已识别，尚未启用自动开锁");
                }finally{try{ndef.close();}catch(Exception ignored){}}
            }catch(Exception e){show(e.getMessage()==null?"读取标签失败":e.getMessage());}
            finally{handling.set(false);}
        });
    }
    private boolean hasOwnRecord(NdefMessage message){for(NdefRecord r:message.getRecords())if("android.com:pkg".equals(new String(r.getType(),StandardCharsets.UTF_8))&&getPackageName().equals(new String(r.getPayload(),StandardCharsets.UTF_8)))return true;return false;}
    private String macFrom(NdefMessage message)throws Exception{
        if(message==null||message.getRecords().length==0)return "";
        Uri uri=message.getRecords()[0].toUri();
        if(uri==null||!"https".equals(uri.getScheme())||!"biz.confong.cn".equals(uri.getHost())||!"/nfc/lock".equals(uri.getPath()))return "";
        String raw=uri.getQueryParameter("mac");return raw==null?"":normalizeMac(raw);
    }
    static String normalizeMac(String value){
        String raw=value.replace(":","").replace("-","").trim().toUpperCase(Locale.ROOT);
        if(!raw.matches("[0-9A-F]{12}"))throw new IllegalArgumentException("门锁 MAC 格式错误");
        StringBuilder out=new StringBuilder();for(int i=0;i<12;i+=2){if(i>0)out.append(':');out.append(raw,i,i+2);}return out.toString();
    }
    private boolean hasBluetooth(){return Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    protected final void unlockFromWidget(){startUnlock(true);}
    private void addWidget(){
        android.appwidget.AppWidgetManager manager=android.appwidget.AppWidgetManager.getInstance(this);
        if(manager.isRequestPinAppWidgetSupported()){
            boolean requested=manager.requestPinAppWidget(new android.content.ComponentName(this,UnlockWidget.class),null,null);
            show(requested?"请在桌面提示中添加卡片；添加后点「一键开门」即可":"请长按桌面，进入组件，选择「胡桃碰一碰」");
        }else show("请长按桌面，进入组件，选择「胡桃碰一碰」开锁卡片");
    }
    private void requestBluetooth(){if(Build.VERSION.SDK_INT>=31)requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},24);}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==24)show(hasBluetooth()?"蓝牙权限已开启，请再点一次操作":"需要附近设备权限才能连接门锁");if(request==25)show(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED?"通知已允许，请开启锁屏监听":"锁屏监听需要显示运行通知");}
    private void startUnlock(boolean reallyOpen){
        if(!hasBluetooth()){requestBluetooth();return;}
        final String mac=prefs.getString("mac","");
        if(mac.isEmpty()||!KeyStore.has(this)){show("请先导入门锁密钥");return;}
        if(!opening.compareAndSet(false,true))return;
        open.setEnabled(false);writeTag.setEnabled(false);
        work.execute(()->{
            LockEngine current=new LockEngine();engine=current;
            try{
                current.run(this,mac,KeyStore.read(this),reallyOpen,this::show);
                if(reallyOpen){prefs.edit().putBoolean("validated",true).apply();Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);if(v!=null)v.vibrate(VibrationEffect.createOneShot(80,VibrationEffect.DEFAULT_AMPLITUDE));}
                writeResult(true,reallyOpen?"门锁已确认开锁成功":"钥匙验证通过，未发送开锁指令");
            }catch(Exception e){String message=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();show(message);writeResult(false,message);}
            finally{engine=null;opening.set(false);runOnUiThread(()->{open.setEnabled(true);writeTag.setEnabled(true);refresh();});}
        });
    }
    private void writeResult(boolean ok,String message){try{JSONObject result=new JSONObject().put("ok",ok).put("message",message).put("timestamp",System.currentTimeMillis());writeFile("last-result.json",result.toString());}catch(Exception ignored){}}
    private void importKey(){
        EditText input=new EditText(this);input.setHint("MAC 密钥");input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setMinLines(2);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("导入自己的门锁密钥").setMessage("粘贴一行：MAC、一个空格、32 位密钥。密钥仅加密保存在本机。").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{String[] parts=input.getText().toString().trim().split("[\\s,，]+");if(parts.length!=2)throw new Exception("请按 MAC 密钥 的格式输入");String mac=normalizeMac(parts[0]);KeyStore.save(this,parts[1]);prefs.edit().putString("mac",mac).putBoolean("validated",false).putBoolean("auto",false).remove("tagId").remove("original").remove("report").apply();auto.setChecked(false);refresh();show("密钥已加密保存，请先验证开锁");dialog.dismiss();}
            catch(Exception e){input.setError(e.getMessage());}
        }));dialog.show();
    }
    private void confirmWrite(boolean restore){
        if(opening.get())return;
        if(prefs.getString("original","").isEmpty()){show("请先读取这把门锁的原标签");return;}
        if(!restore&&!prefs.getBoolean("validated",false)){show("请先完成一次手动开锁验证");return;}
        String message=restore?"下一次碰到已登记的门锁标签时，将恢复最初读取的原内容。":"下一次碰到已登记的门锁标签时，将保留原网址和天猫校园入口，并优先使用胡桃碰一碰。其他手机若两款应用都没安装，应用商店跳转可能改变。只处理这一把门锁。";
        new AlertDialog.Builder(this).setTitle(restore?"恢复原标签":"配置碰一碰开门").setMessage(message).setNegativeButton("取消",null).setPositiveButton(restore?"准备恢复":"准备配置",(d,w)->{mode=restore?2:1;nfcLaunch=false;enableReader();show("请保持本页打开，再碰一下这把门锁标签");}).show();
    }
    private void write(Ndef ndef,Tag tag,NdefMessage current,boolean restore)throws Exception{
        mode=0;
        if(!prefs.getString("tagId","").equals(LockProtocol.hex(tag.getId())))throw new Exception("不是已登记的标签，未写入");
        byte[] original=LockProtocol.bytes(prefs.getString("original",""));NdefMessage originalMessage=new NdefMessage(original),desired=originalMessage;
        if(!restore){
            desired=configuredMessage(originalMessage);
            if(current==null||(!Arrays.equals(current.toByteArray(),original)&&!Arrays.equals(current.toByteArray(),desired.toByteArray())))throw new Exception("原标签内容发生了变化，未写入");
        }
        if(!ndef.isWritable())throw new Exception("标签不可写，未修改");
        if(desired.toByteArray().length>ndef.getMaxSize())throw new Exception("标签容量不足，未修改");
        ndef.writeNdefMessage(desired);NdefMessage readback=ndef.getNdefMessage();
        if(readback==null||!Arrays.equals(desired.toByteArray(),readback.toByteArray()))throw new Exception("写入后未确认，请保持靠近并重新读取标签");
        lastTap=SystemClock.elapsedRealtime();
        prefs.edit().putLong("lastNfcAt",System.currentTimeMillis()).putBoolean("configured",!restore).putBoolean("auto",!restore&&prefs.getBoolean("validated",false)).putString("report",restore?"原标签内容已恢复":"标签已配置，优先胡桃碰一碰；原网址和天猫校园入口均保留").apply();
        runOnUiThread(()->{auto.setChecked(prefs.getBoolean("auto",false));refresh();});show(restore?"标签原内容已恢复":"标签已配置，请回到桌面测试碰一碰");
    }
    private void consumeSetup(){
        File setup=new File(getFilesDir(),"setup.json");if(!setup.exists())return;
        try{JSONObject j=new JSONObject(new String(readFile(setup),StandardCharsets.UTF_8));String mac=normalizeMac(j.getString("mac"));if(j.has("key"))KeyStore.save(this,j.getString("key"));SharedPreferences.Editor e=prefs.edit().putString("mac",mac);if(j.has("tagId"))e.putString("tagId",j.getString("tagId"));if(j.has("original"))e.putString("original",j.getString("original"));e.apply();}
        catch(Exception ignored){}finally{setup.delete();}
    }
    private void writePlan(){
        try{
            String original=prefs.getString("original","");if(original.isEmpty())return;
            NdefMessage before=new NdefMessage(LockProtocol.bytes(original));
            NdefMessage after=configuredMessage(before);
            int matched=0;NdefRecord[] originals=before.getRecords();for(NdefRecord r:after.getRecords())if(matched<originals.length&&originals[matched].equals(r))matched++;
            boolean preserved=matched==originals.length;
            JSONObject plan=new JSONObject().put("originalBytes",before.toByteArray().length).put("newBytes",after.toByteArray().length).put("preservesOriginalRecords",preserved).put("firstApp",getPackageName()).put("recordCount",after.getRecords().length).put("storeFallbackChanges",true);
            writeFile("tag-plan.json",plan.toString());
        }catch(Exception ignored){}
    }
    private NdefMessage configuredMessage(NdefMessage original){
        List<NdefRecord> records=new ArrayList<>(Arrays.asList(original.getRecords()));
        records.add(1,NdefRecord.createApplicationRecord(getPackageName()));
        return new NdefMessage(records.toArray(new NdefRecord[0]));
    }
    private byte[] readFile(File file)throws Exception{try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    private void writeFile(String name,String value)throws Exception{try(FileOutputStream out=openFileOutput(name,MODE_PRIVATE)){out.write(value.getBytes(StandardCharsets.UTF_8));}}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable round(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(20));return d;}
    private TextView text(String value,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(dp(4),1);if(bold)v.setTypeface(null,Typeface.BOLD);return v;}
    private Button button(String label,int color){Button b=new Button(this);b.setText(label);b.setTextSize(16);b.setAllCaps(false);b.setTextColor(fg);b.setBackground(round(color));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54));p.topMargin=dp(14);b.setLayoutParams(p);return b;}
}
