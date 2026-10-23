package com.hutao.touchkey;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Compatibility entry for cards created before direct service PendingIntents. */
public final class WidgetUnlockActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try{if(state==null)startForegroundService(new Intent(this,UnlockService.class));}
        catch(Exception e){Toast.makeText(this,"无法开始开锁，请重新添加桌面卡片",Toast.LENGTH_LONG).show();}
        finish();
    }
}
