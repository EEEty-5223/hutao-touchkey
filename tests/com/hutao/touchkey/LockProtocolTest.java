package com.hutao.touchkey;

import java.util.Arrays;
import javax.crypto.Cipher;

public final class LockProtocolTest {
    private static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);}
    public static void main(String[] args)throws Exception{
        byte[] key=LockProtocol.bytes("000102030405060708090A0B0C0D0E0F");
        byte[] plain=LockProtocol.bytes("00112233445566778899AABBCCDDEEFF");
        byte[] cipher=LockProtocol.crypt(plain,key,Cipher.ENCRYPT_MODE);
        check(LockProtocol.hex(cipher).equals("69C4E0D86A7B0430D8CDB78070B4C55A"),"NIST AES encryption");
        check(Arrays.equals(plain,LockProtocol.crypt(cipher,key,Cipher.DECRYPT_MODE)),"AES decrypt");
        byte[] reply=LockProtocol.bytes("55AAFF0D00110000112233445566778899AABBCCDDEEFF");
        check(reply.length==23&&Arrays.equals(plain,LockProtocol.nonce(reply)),"23 byte nonce reply");
        boolean rejected=false;try{LockProtocol.nonce(Arrays.copyOf(reply,22));}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"Reject truncated nonce");
        check(LockProtocol.hex(LockProtocol.session(plain,key)).equals("00102030405060708090A0B0C0D0E0F0"),"Session XOR");
        byte[] packet=LockProtocol.unlock(key);
        check(packet.length==21&&LockProtocol.starts(packet,"55AA0010"),"Packet envelope");
        check(LockProtocol.hex(LockProtocol.decrypt(packet,key)).equals("FF0B0000090000000000000000000000"),"Unlock plaintext and inner checksum");
        byte[] noisy=new byte[24];noisy[0]=0x55;System.arraycopy(packet,0,noisy,3,21);
        check(Arrays.equals(packet,LockProtocol.frame(noisy,LockProtocol.bytes("55AA0010"),21)),"Frame noise recovery");
        packet[20]^=1;
        check(LockProtocol.frame(packet,LockProtocol.bytes("55AA0010"),21)==null,"Reject corrupt checksum");
        System.out.println("9 protocol checks passed");
    }
}
