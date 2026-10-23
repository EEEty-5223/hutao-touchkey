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
        byte[] plain=LockProtocol.bytes(value);if(plain.length!=16)throw new IllegalArgumentException("密钥应为 32 位十六进制字符");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        c.getSharedPreferences("private-key",0).edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).putString("encrypted",Base64.encodeToString(cipher.doFinal(plain),Base64.NO_WRAP)).commit();
    }
    static byte[] read(Context c)throws Exception{
        android.content.SharedPreferences p=c.getSharedPreferences("private-key",0);
        String value=p.getString("encrypted","");if(value.isEmpty())throw new Exception("请先导入你的门锁密钥");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p.getString("iv",""),Base64.NO_WRAP)));return cipher.doFinal(Base64.decode(value,Base64.NO_WRAP));
    }
    static boolean has(Context c){return !c.getSharedPreferences("private-key",0).getString("encrypted","").isEmpty();}
}
