package com.hutao.touchkey;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SuppressLint("MissingPermission")
final class LockEngine {
    interface Progress{void show(String message);}
    private static final UUID WRITE=UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e0001"),NOTIFY=UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e0002"),CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final CountDownLatch connected=new CountDownLatch(1),discovered=new CountDownLatch(1),descriptor=new CountDownLatch(1);
    private final Object monitor=new Object();
    private final ByteArrayOutputStream received=new ByteArrayOutputStream();
    private volatile String failure="";
    private volatile boolean closed;
    private BluetoothGatt gatt;
    private Context context;
    private String stage="连接";
    private byte[] lastNotification;
    private long lastNotificationAt;
    private BluetoothGattCharacteristic write;
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){
            if(status!=BluetoothGatt.GATT_SUCCESS){fail("蓝牙连接失败（"+status+"）");return;}
            if(state==BluetoothProfile.STATE_CONNECTED)connected.countDown();
            else if(state==BluetoothProfile.STATE_DISCONNECTED&&!closed)fail("蓝牙连接已断开");
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status){if(status!=BluetoothGatt.GATT_SUCCESS)fail("读取蓝牙服务失败");discovered.countDown();}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){if(status!=BluetoothGatt.GATT_SUCCESS)fail("开启门锁应答失败");descriptor.countDown();}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){if(NOTIFY.equals(c.getUuid()))append(c.getValue());}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){if(NOTIFY.equals(c.getUuid()))append(value);}
    };
    private void fail(String message){failure=message;connected.countDown();discovered.countDown();descriptor.countDown();synchronized(monitor){monitor.notifyAll();}}
    private void append(byte[] data){if(data==null)return;synchronized(monitor){long now=android.os.SystemClock.elapsedRealtime();if(now-lastNotificationAt<5&&Arrays.equals(lastNotification,data))return;lastNotification=data.clone();lastNotificationAt=now;if(received.size()+data.length>4096)received.reset();received.write(data,0,data.length);monitor.notifyAll();}}
    private void check()throws Exception{if(closed)throw new Exception("操作已取消");if(!failure.isEmpty())throw new Exception(failure);}
    private void await(CountDownLatch latch,int seconds,String timeout)throws Exception{if(!latch.await(seconds,TimeUnit.SECONDS))throw new Exception(timeout);check();}
    void run(Context context,String mac,byte[] activationKey,boolean sendUnlock,Progress progress)throws Exception{
        this.context=context.getApplicationContext();
        try{
            BluetoothManager manager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter==null||!adapter.isEnabled())throw new Exception("请先打开手机蓝牙");
            progress.show("正在连接门锁，请保持靠近…");
            gatt=adapter.getRemoteDevice(mac).connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);
            if(gatt==null)throw new Exception("无法创建蓝牙连接");
            await(connected,12,"连接超时，请靠近门锁后重试");
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            if(!gatt.discoverServices())throw new Exception("无法读取蓝牙服务");
            await(discovered,8,"读取门锁蓝牙服务超时");
            BluetoothGattCharacteristic notify=null;
            for(BluetoothGattService service:gatt.getServices()){
                if(service.getCharacteristic(WRITE)!=null)write=service.getCharacteristic(WRITE);
                if(service.getCharacteristic(NOTIFY)!=null)notify=service.getCharacteristic(NOTIFY);
            }
            if(write==null||notify==null)throw new Exception("这把锁的蓝牙协议与当前工具不匹配");
            if(!gatt.setCharacteristicNotification(notify,true))throw new Exception("无法启用门锁通知");
            BluetoothGattDescriptor cccd=notify.getDescriptor(CCCD);
            if(cccd!=null){
                boolean accepted;
                if(Build.VERSION.SDK_INT>=33)accepted=gatt.writeDescriptor(cccd,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)==BluetoothStatusCodes.SUCCESS;
                else{cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);accepted=gatt.writeDescriptor(cccd);}
                if(!accepted)throw new Exception("开启门锁应答失败");
                await(descriptor,3,"等待门锁通知配置超时");
            }
            stage="随机数请求";progress.show("正在请求门锁校验…");
            byte[] random=exchange(LockProtocol.RANDOM_REQUEST,"55AAFF0D0011",23,2500);
            byte[] session=LockProtocol.session(LockProtocol.nonce(random),activationKey);
            stage="钥匙验证";progress.show("正在确认钥匙…");
            byte[] linked=exchange(LockProtocol.LINK_REQUEST,"55AA0010",21,2500);
            if(!LockProtocol.starts(LockProtocol.decrypt(linked,session),"FF130001"))throw new Exception("门锁未确认钥匙，请检查密钥是否仍有效");
            if(!sendUnlock){progress.show("钥匙验证通过，本次未开锁");return;}
            progress.show("钥匙验证通过，正在开锁…");
            stage="开锁确认";
            byte[] response;
            try{response=exchange(LockProtocol.unlock(session),"55AA0010",21,3500);}
            catch(Exception e){throw new Exception("开锁指令已尝试发送，但未收到确认，请观察门锁实际状态。",e);}
            if(!LockProtocol.starts(LockProtocol.decrypt(response,session),"FF0B0001000A"))throw new Exception("门锁没有返回开锁成功标志");
            progress.show("门锁已确认开锁成功");
        }finally{close();}
    }
    private byte[] exchange(byte[] packet,String prefix,int length,long timeout)throws Exception{
        check();synchronized(monitor){received.reset();}
        int type=(write.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        for(int offset=0;offset<packet.length;offset+=20){
            byte[] part=Arrays.copyOfRange(packet,offset,Math.min(offset+20,packet.length));boolean accepted=false;
            for(int attempt=0;attempt<2&&!accepted;attempt++){
                check();
                if(Build.VERSION.SDK_INT>=33)accepted=gatt.writeCharacteristic(write,part,type)==BluetoothStatusCodes.SUCCESS;
                else{write.setWriteType(type);write.setValue(part);accepted=gatt.writeCharacteristic(write);}
                if(!accepted)Thread.sleep(80);
            }
            if(!accepted)throw new Exception("蓝牙发送失败");
            if(offset+20<packet.length)Thread.sleep(70);
        }
        long end=android.os.SystemClock.elapsedRealtime()+timeout;
        synchronized(monitor){
            while(true){
                check();byte[] result=LockProtocol.frame(received.toByteArray(),LockProtocol.bytes(prefix),length,length!=23);if(result!=null)return result;
                long left=end-android.os.SystemClock.elapsedRealtime();if(left<=0){diagnostic(received.toByteArray());throw new Exception(stage+"超时（收到 "+received.size()+" 字节）");}monitor.wait(Math.min(left,100));
            }
        }
    }
    private void diagnostic(byte[] value){try{org.json.JSONObject j=new org.json.JSONObject().put("stage",stage).put("receivedBytes",value.length).put("firstSixBytes",LockProtocol.hex(Arrays.copyOf(value,Math.min(6,value.length))));try(java.io.FileOutputStream out=context.openFileOutput("protocol-diagnostic.json",Context.MODE_PRIVATE)){out.write(j.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}}catch(Exception ignored){}}
    void close(){closed=true;BluetoothGatt g=gatt;gatt=null;if(g!=null){try{g.disconnect();}catch(Exception ignored){}try{g.close();}catch(Exception ignored){}}synchronized(monitor){monitor.notifyAll();}}
}
