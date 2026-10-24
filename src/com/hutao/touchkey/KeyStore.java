package com.hutao.touchkey;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class KeyStore {
    private static SecretKey key()throws Exception{
        java.security.KeyStore store=java.security.KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias("touchkey-local"))return (SecretKey)store.getKey("touchkey-local",null);
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder("touchkey-local",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return gen.generateKey();
    }
    static void save(Context c,String value)throws Exception{
        save(c,KeyProfiles.current(c).getString("mac",""),value);
    }
    static void save(Context c,String mac,String value)throws Exception{
        byte[] plain=LockProtocol.bytes(value);if(plain.length!=16)throw new IllegalArgumentException("密钥应为 32 位十六进制字符");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        if(!storage(c,mac).edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).putString("encrypted",Base64.encodeToString(cipher.doFinal(plain),Base64.NO_WRAP)).commit())throw new Exception("密钥保存失败");
    }
    static byte[] read(Context c)throws Exception{
        return read(c,KeyProfiles.current(c).getString("mac",""));
    }
    static byte[] read(Context c,String mac)throws Exception{
        android.content.SharedPreferences p=storage(c,mac);
        String value=p.getString("encrypted","");if(value.isEmpty())throw new Exception("请先导入你的门锁密钥");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p.getString("iv",""),Base64.NO_WRAP)));return cipher.doFinal(Base64.decode(value,Base64.NO_WRAP));
    }
    private static android.content.SharedPreferences storage(Context c,String mac){return c.getSharedPreferences("private-key-"+mac.replace(":",""),0);}
    static boolean has(Context c){return has(c,KeyProfiles.current(c).getString("mac",""));}
    static boolean has(Context c,String mac){return !storage(c,mac).getString("encrypted","").isEmpty();}
    static void migrate(Context c,String mac){
        android.content.SharedPreferences old=c.getSharedPreferences("private-key",0),next=storage(c,mac);
        if(!has(c,mac)&&!old.getString("encrypted","").isEmpty())
            if(!next.edit().putString("iv",old.getString("iv","")).putString("encrypted",old.getString("encrypted","")).commit())throw new IllegalStateException("旧密钥迁移失败");
    }
}
