package com.hutao.touchkey;

import java.security.SecureRandom;

/** One-time short-lived challenge. No door keys are sent to the wearable. */
final class WristSession {
    private final SecureRandom random=new SecureRandom();
    private String token="";
    private long issuedAt,lastAccepted=-8000;
    synchronized String issue(long now){
        byte[] value=new byte[16];random.nextBytes(value);token=LockProtocol.hex(value);issuedAt=now;return token;
    }
    synchronized boolean consume(String value,long now){
        if(token.isEmpty()||!token.equals(value)||now-issuedAt>15000||now<issuedAt||now-lastAccepted<8000)return false;
        token="";lastAccepted=now;return true;
    }
    synchronized void clear(){token="";}
}
