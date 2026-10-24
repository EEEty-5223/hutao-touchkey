package com.hutao.touchkey;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.nfc.*;
import java.util.*;

/** Each door has isolated settings and ciphertext; NFC never changes the manual default. */
final class KeyProfiles {
    private static final String[] STRINGS={"mac","tagId","original","report"};
    static SharedPreferences global(Context c){return c.getSharedPreferences("settings",0);}
    static SharedPreferences profile(Context c,String mac){return c.getSharedPreferences("door-"+mac.replace(":",""),0);}
    static synchronized void migrate(Context c){
        SharedPreferences g=global(c);
        if(g.getBoolean("multiKeyMigrated",false))return;
        String mac=g.getString("mac","");
        if(!mac.isEmpty()){
            SharedPreferences.Editor e=profile(c,mac).edit();
            for(String s:STRINGS)e.putString(s,g.getString(s,""));
            for(String s:new String[]{"validated","configured","auto"})e.putBoolean(s,g.getBoolean(s,false));
            if(!e.commit())throw new IllegalStateException("旧钥匙迁移失败，请重试");
            KeyStore.migrate(c,mac);
            if(!g.edit().putStringSet("doors",new HashSet<>(Arrays.asList(mac))).putString("selectedDoor",mac).commit())throw new IllegalStateException("保存钥匙列表失败");
        }
        if(!g.edit().putBoolean("multiKeyMigrated",true).commit())throw new IllegalStateException("保存迁移状态失败，请重试");
    }
    static List<String> list(Context c){migrate(c);List<String> result=new ArrayList<>(global(c).getStringSet("doors",Collections.emptySet()));Collections.sort(result);return result;}
    static SharedPreferences current(Context c){migrate(c);return profile(c,global(c).getString("selectedDoor",""));}
    static synchronized void add(Context c,String mac,String secret)throws Exception{
        migrate(c);boolean existed=list(c).contains(mac);
        KeyStore.save(c,mac,secret);
        SharedPreferences.Editor e=profile(c,mac).edit().putString("mac",mac).putBoolean("validated",false).putBoolean("auto",false);
        if(!existed)e.putString("name","钥匙 "+(list(c).size()+1));
        if(!e.commit())throw new Exception("保存钥匙失败");
        Set<String> ids=new HashSet<>(global(c).getStringSet("doors",Collections.emptySet()));ids.add(mac);
        if(!global(c).edit().putStringSet("doors",ids).putString("selectedDoor",mac).commit())throw new Exception("保存钥匙列表失败");
    }
    static void select(Context c,String mac){if(!list(c).contains(mac))throw new IllegalArgumentException("钥匙不存在");global(c).edit().putString("selectedDoor",mac).commit();}
    static String label(Context c,String mac){return profile(c,mac).getString("name","钥匙")+" · "+mac;}
    static String macFrom(NdefMessage message){
        if(message==null)return "";
        for(NdefRecord r:message.getRecords())try{
            Uri uri=r.toUri();
            if(uri!=null&&"https".equals(uri.getScheme())&&"biz.confong.cn".equals(uri.getHost())&&"/nfc/lock".equals(uri.getPath())){
                String raw=uri.getQueryParameter("mac");if(raw!=null)return MainActivity.normalizeMac(raw);
            }
        }catch(IllegalArgumentException ignored){}
        return "";
    }
    static SharedPreferences match(Context c,NdefMessage message,String tagId,boolean requireBound){
        String mac=macFrom(message);if(mac.isEmpty()||!list(c).contains(mac))return null;
        SharedPreferences p=profile(c,mac);String known=p.getString("tagId","");
        if((requireBound&&known.isEmpty())||(!known.isEmpty()&&!known.equals(tagId)))return null;
        return KeyStore.has(c,mac)?p:null;
    }
    static boolean ready(Context c,SharedPreferences p){return p.getBoolean("auto",false)&&p.getBoolean("validated",false)&&p.getBoolean("configured",false)&&!p.getString("tagId","").isEmpty()&&KeyStore.has(c,p.getString("mac",""));}
    static boolean recentTap(SharedPreferences p,long now){long previous=p.getLong("lastNfcAt",0);long elapsed=now-previous;return previous!=0&&elapsed>=0&&elapsed<8000;}
    static boolean anyReady(Context c){for(String mac:list(c))if(ready(c,profile(c,mac)))return true;return false;}
}
