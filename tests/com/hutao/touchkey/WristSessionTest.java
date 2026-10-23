package com.hutao.touchkey;
public final class WristSessionTest {
    private static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);}
    public static void main(String[] args){
        WristSession s=new WristSession();check(!s.consume("",0),"No unissued token");
        String a=s.issue(0);check(a.matches("[0-9A-F]{32}"),"128 bit challenge");
        check(!s.consume("wrong",1),"Reject wrong token");check(s.consume(a,1),"Accept current token");
        check(!s.consume(a,2),"Reject replay");String b=s.issue(2);check(!a.equals(b),"Fresh challenge");
        check(!s.consume(b,3),"Cooldown across challenges");check(s.consume(b,8001),"Cooldown expires");
        String c=s.issue(9000);check(!s.consume(c,25001),"Expired challenge");
        String d=s.issue(26000);check(!s.consume(d,25999),"Reject backwards time");s.clear();check(!s.consume(d,26001),"Stop invalidates challenge");
        String e=s.issue(30000),f=s.issue(30001);check(!s.consume(e,30002)&&s.consume(f,30002),"Only latest token valid");
        System.out.println("12 wrist session checks passed");
    }
}
