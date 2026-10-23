package com.hutao.touchkey;

import java.util.Arrays;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

final class LockProtocol {
    static final byte[] RANDOM_REQUEST=bytes("55AAFF0D00000B");
    static final byte[] LINK_REQUEST=bytes("55AAFF13000011");
    static byte[] bytes(String value){
        String s=value.replace(":", "").replace(" ", "");
        if((s.length()&1)!=0||!s.matches("[0-9a-fA-F]*"))throw new IllegalArgumentException("十六进制格式错误");
        byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;
    }
    static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02X",b&255));return s.toString();}
    static int sum(byte[] value,int end){int n=0;for(int i=0;i<end;i++)n+=value[i]&255;return n&255;}
    static byte[] frame(byte[] buffer,byte[] prefix,int length){
        return frame(buffer,prefix,length,true);
    }
    static byte[] frame(byte[] buffer,byte[] prefix,int length,boolean checksum){
        for(int i=0;i+length<=buffer.length;i++){
            boolean match=true;for(int j=0;j<prefix.length;j++)if(buffer[i+j]!=prefix[j]){match=false;break;}
            if(match){byte[] frame=Arrays.copyOfRange(buffer,i,i+length);if(!checksum||sum(frame,length-1)==(frame[length-1]&255))return frame;}
        }return null;
    }
    static byte[] nonce(byte[] frame){
        byte[] f=frame(frame,bytes("55AAFF0D0011"),23,false);
        if(f==null)throw new IllegalArgumentException("门锁随机数应答无效");
        return Arrays.copyOfRange(f,7,23);
    }
    static byte[] session(byte[] nonce,byte[] key){
        if(nonce.length!=16||key.length!=16)throw new IllegalArgumentException("密钥长度应为 16 字节");
        byte[] session=new byte[16];for(int i=0;i<16;i++)session[i]=(byte)(nonce[i]^key[i]);return session;
    }
    static byte[] crypt(byte[] value,byte[] key,int mode)throws Exception{Cipher c=Cipher.getInstance("AES/ECB/NoPadding");c.init(mode,new SecretKeySpec(key,"AES"));return c.doFinal(value);}
    static byte[] decrypt(byte[] frame,byte[] key)throws Exception{
        byte[] f=frame(frame,bytes("55AA0010"),21);if(f==null)throw new IllegalArgumentException("门锁加密应答无效");
        return crypt(Arrays.copyOfRange(f,4,20),key,Cipher.DECRYPT_MODE);
    }
    static byte[] unlock(byte[] key)throws Exception{
        byte[] plain=new byte[16];byte[] body=bytes("FF0B0000");System.arraycopy(body,0,plain,0,body.length);
        plain[4]=(byte)sum(bytes("55AAFF0B0000"),6);
        byte[] cipher=crypt(plain,key,Cipher.ENCRYPT_MODE);byte[] packet=new byte[21];
        byte[] header=bytes("55AA0010");System.arraycopy(header,0,packet,0,4);System.arraycopy(cipher,0,packet,4,16);packet[20]=(byte)sum(packet,20);return packet;
    }
    static boolean starts(byte[] value,String prefix){byte[] p=bytes(prefix);if(value.length<p.length)return false;for(int i=0;i<p.length;i++)if(value[i]!=p[i])return false;return true;}
}
