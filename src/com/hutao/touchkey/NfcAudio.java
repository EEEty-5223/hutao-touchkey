package com.hutao.touchkey;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.nfc.*;
import android.nfc.tech.Ndef;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/** Recognition feedback, independent of BLE authentication and its result. */
final class NfcAudio {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static MediaPlayer player;
    private static long lastStarted;
    private NfcAudio(){}

    static void onTag(Context context,Tag tag){
        if(tag==null)return;
        SharedPreferences prefs=context.getSharedPreferences("settings",0);
        String known=prefs.getString("tagId","");
        if(!known.isEmpty()&&!known.equals(LockProtocol.hex(tag.getId())))return;
        String configured=prefs.getString("mac","").replace(":","");
        if(configured.isEmpty())return;
        Ndef ndef=Ndef.get(tag);
        NdefMessage message=ndef==null?null:ndef.getCachedNdefMessage();
        if(message==null)return;
        for(NdefRecord record:message.getRecords()){
            Uri uri=record.toUri();
            if(uri==null||!"https".equals(uri.getScheme())||!"biz.confong.cn".equals(uri.getHost())||!"/nfc/lock".equals(uri.getPath()))continue;
            String mac=uri.getQueryParameter("mac");
            if(mac!=null&&configured.equalsIgnoreCase(mac.replace(":",""))){play(context);return;}
        }
    }

    static void play(Context context){
        Context app=context.getApplicationContext();
        MAIN.post(()->{
            long now=SystemClock.elapsedRealtime();
            // The same tap can reach both cached and freshly read NDEF paths.
            if(player!=null||(lastStarted!=0&&now-lastStarted<1500))return;
            MediaPlayer next=null;
            try{
                AudioAttributes attributes=new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
                next=MediaPlayer.create(app,ht.key.R.raw.nfc_recognized,attributes,0);
                if(next==null)return;
                player=next;
                next.setOnCompletionListener(NfcAudio::release);
                next.setOnErrorListener((mp,what,extra)->{release(mp);return true;});
                next.start();lastStarted=now;
            }catch(RuntimeException e){
                if(next!=null)release(next);
                Log.w("NfcAudio","Unable to play NFC recognition sound",e);
            }
        });
    }
    private static void release(MediaPlayer mp){
        if(player==mp)player=null;
        mp.release();
    }
}