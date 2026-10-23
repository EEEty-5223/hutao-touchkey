package com.hutao.touchkey;

import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.*;
import android.content.Context;

/** Experimental IPC verified on this firmware. No keyguard or settings writes. */
final class LockedNfc {
    private static final String TESTED_BUILD="vivo/PD2408/PD2408:16/BP2A.250605.031.A3_V000L1/compiler260710223223:user/release-keys";
    interface Callback { void tag(Tag tag); }
    private final IBinder service;
    private final Binder handler;
    private boolean registered;
    static boolean supported(){return Build.VERSION.SDK_INT==36&&TESTED_BUILD.equals(Build.FINGERPRINT);}
    LockedNfc(Context context,Callback callback)throws Exception{
        if(!supported())throw new Exception("此系统版本尚未验证锁屏 NFC，请先完成设备适配");
        NfcAdapter adapter=NfcAdapter.getDefaultAdapter(context);
        if(adapter==null||!adapter.isEnabled())throw new Exception("请先开启 NFC");
        service=((IInterface)NfcAdapter.class.getMethod("getService").invoke(adapter)).asBinder();
        if(!"android.nfc.INfcAdapter".equals(service.getInterfaceDescriptor()))throw new Exception("不支持此 NFC 系统接口");
        handler=new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code==INTERFACE_TRANSACTION){reply.writeString("android.nfc.INfcUnlockHandler");return true;}
            if(code!=FIRST_CALL_TRANSACTION)return super.onTransact(code,data,reply,flags);
            data.enforceInterface("android.nfc.INfcUnlockHandler");
            Tag tag=data.readTypedObject(Tag.CREATOR);
            try{if(tag!=null)callback.tag(tag);}catch(RuntimeException ignored){}
            // This app authenticates only to the door, never to the phone keyguard.
            reply.writeNoException();reply.writeBoolean(false);return true;
        }};
    }
    void register()throws Exception{transact(true);registered=true;}
    void unregister()throws Exception{if(registered){transact(false);registered=false;}}
    private void transact(boolean add)throws Exception{
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken("android.nfc.INfcAdapter");data.writeStrongBinder(handler);
            if(add)data.writeIntArray(new int[]{1});
            // Codes 17/18 were obtained from INfcAdapter.Stub on TESTED_BUILD.
            // The service still enforces WRITE_SECURE_SETTINGS; no permission is bypassed.
            if(!service.transact(add?17:18,data,reply,0))throw new Exception("NFC 接口版本不匹配");
            reply.readException();
        }finally{data.recycle();reply.recycle();}
    }
}
