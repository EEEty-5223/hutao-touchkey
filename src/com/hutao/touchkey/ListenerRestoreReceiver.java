package com.hutao.touchkey;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;

/** Restore only a previously enabled listener, after credential storage is available. */
public final class ListenerRestoreReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        String action=intent==null?null:intent.getAction();
        if(Intent.ACTION_BOOT_COMPLETED.equals(action)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))restore(context);
    }
    static void restore(Context context){
        SharedPreferences p=context.getSharedPreferences("settings",0);
        if(LockScreenService.RUNNING||!LockedNfc.supported()||!p.getBoolean("lockedNfc",false)||!p.getBoolean("auto",false)||!p.getBoolean("validated",false)||!p.getBoolean("configured",false)||p.getString("tagId","").isEmpty()||!KeyStore.has(context))return;
        if(context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)!=PackageManager.PERMISSION_GRANTED||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED||context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;
        NfcAdapter nfc=NfcAdapter.getDefaultAdapter(context);if(nfc==null||!nfc.isEnabled())return;
        try{context.startForegroundService(new Intent(context,LockScreenService.class));}
        catch(RuntimeException e){p.edit().putString("lockedNfcStatus","自动恢复被系统限制，请打开应用恢复监听").apply();}
    }
}
