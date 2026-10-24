"""Host regression tests for the real KeyProfiles routing code, with Android/storage shims.
Does not exercise device NFC, Android Keystore encryption, or BLE hardware.
"""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
sources={
'android/content/SharedPreferences.java': '''package android.content;
import java.util.*;
public class SharedPreferences {
 private final Map<String,Object> data=new HashMap<>();
 public String getString(String k,String d){return (String)data.getOrDefault(k,d);}
 public boolean getBoolean(String k,boolean d){return (Boolean)data.getOrDefault(k,d);}
 public long getLong(String k,long d){return (Long)data.getOrDefault(k,d);}
 @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String> d){return (Set<String>)data.getOrDefault(k,d);}
 public Editor edit(){return new Editor();}
 public class Editor {
  Map<String,Object> pending=new HashMap<>();
  public Editor putString(String k,String v){pending.put(k,v);return this;}
  public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
  public Editor putLong(String k,long v){pending.put(k,v);return this;}
  public Editor putStringSet(String k,Set<String> v){pending.put(k,new HashSet<>(v));return this;}
  public boolean commit(){data.putAll(pending);return true;}
  public void apply(){commit();}
 }
}''',
'android/content/Context.java': '''package android.content;import java.util.*;
public class Context {private Map<String,SharedPreferences> files=new HashMap<>();
public SharedPreferences getSharedPreferences(String n,int m){return files.computeIfAbsent(n,k->new SharedPreferences());}}''',
'android/net/Uri.java': '''package android.net;import java.net.*;
public class Uri {private URI u;public Uri(String s){u=URI.create(s);}
public String getScheme(){return u.getScheme();}public String getHost(){return u.getHost();}public String getPath(){return u.getPath();}
public String getQueryParameter(String k){String q=u.getRawQuery();if(q==null)return null;for(String p:q.split("&")){String[] a=p.split("=",2);if(a[0].equals(k))return a.length>1?a[1]:"";}return null;}}''',
'android/nfc/NdefRecord.java': '''package android.nfc;import android.net.Uri;
public class NdefRecord {private String uri;public NdefRecord(String s){uri=s;}public Uri toUri(){return uri==null?null:new Uri(uri);}}''',
'android/nfc/NdefMessage.java': '''package android.nfc;public class NdefMessage {private NdefRecord[] r;public NdefMessage(NdefRecord... x){r=x;}public NdefRecord[] getRecords(){return r;}}''',
'com/hutao/touchkey/MainActivity.java': '''package com.hutao.touchkey;public class MainActivity {
static String normalizeMac(String v){String s=v.replace(":","").replace("-","").trim().toUpperCase(java.util.Locale.ROOT);if(!s.matches("[0-9A-F]{12}"))throw new IllegalArgumentException();return s.replaceAll("(..)(?!$)","$1:");}}''',
'com/hutao/touchkey/KeyStore.java': '''package com.hutao.touchkey;import android.content.Context;
final class KeyStore {
static void save(Context c,String mac,String key){if(!key.matches("[A-F0-9]{32}"))throw new IllegalArgumentException();c.getSharedPreferences("test-key-"+mac,0).edit().putString("value",key).commit();}
static boolean has(Context c,String mac){return !c.getSharedPreferences("test-key-"+mac,0).getString("value","").isEmpty();}
static void migrate(Context c,String mac){save(c,mac,"00000000000000000000000000000000");}}''',
'com/hutao/touchkey/KeyProfilesTest.java': '''package com.hutao.touchkey;
import android.content.*;import android.nfc.*;
public class KeyProfilesTest {
static int checks;static void ok(boolean b,String n){if(!b)throw new AssertionError(n);checks++;}
static final String A="00:11:22:33:44:55",B="AA:BB:CC:DD:EE:FF";
static NdefMessage tag(String mac){return new NdefMessage(new NdefRecord(null),new NdefRecord("https://biz.confong.cn/nfc/lock?mac="+mac));}
public static void main(String[] args)throws Exception{
 Context c=new Context();SharedPreferences legacy=KeyProfiles.global(c);
 legacy.edit().putString("mac",A).putString("tagId","TAG_A").putString("original","BACKUP_A").putBoolean("validated",true).putBoolean("configured",true).putBoolean("auto",true).commit();
 SharedPreferences a=KeyProfiles.current(c);
 ok(a.getString("mac","").equals(A)&&a.getString("original","").equals("BACKUP_A"),"Legacy metadata migration");
 ok(KeyProfiles.ready(c,a),"Legacy readiness migration");
 KeyProfiles.add(c,B,"11111111111111111111111111111111");SharedPreferences b=KeyProfiles.current(c);
 ok(KeyProfiles.list(c).size()==2,"Adding second key retains first");
 ok(a.getBoolean("auto",false)&&a.getBoolean("validated",false)&&!b.getBoolean("validated",false),"Validation isolated");
 b.edit().putString("tagId","TAG_B").putString("original","BACKUP_B").putBoolean("validated",true).putBoolean("configured",true).putBoolean("auto",true).commit();
 ok(KeyProfiles.match(c,tag("001122334455"),"TAG_A",true)==a,"A NFC selects A while default is B");
 ok(KeyProfiles.match(c,tag("aabbccddeeff"),"TAG_B",true)==b,"B NFC selects B");
 ok(KeyProfiles.current(c)==b,"NFC routing does not change default");
 ok(KeyProfiles.match(c,tag(A),"TAG_B",true)==null,"Reject wrong tag UID");
 ok(KeyProfiles.match(c,tag("112233445566"),"TAG_A",true)==null,"Reject unknown door");
 ok(KeyProfiles.match(c,new NdefMessage(new NdefRecord("https://example.com/nfc/lock?mac="+A)),"TAG_A",true)==null,"Reject foreign host");
 ok(KeyProfiles.match(c,new NdefMessage(new NdefRecord("https://biz.confong.cn/nfc/lock?mac=oops")),"TAG_A",true)==null,"Reject malformed MAC");
 KeyProfiles.select(c,A);ok(KeyProfiles.current(c)==a,"Manual default selection");
 KeyProfiles.add(c,B,"22222222222222222222222222222222");
 ok(KeyProfiles.list(c).size()==2&&!b.getBoolean("validated",true)&&!b.getBoolean("auto",true),"Reimport invalidates only updated key");
 ok(b.getString("original","").equals("BACKUP_B")&&a.getString("original","").equals("BACKUP_A"),"Original tag backups isolated");
 ok(KeyProfiles.anyReady(c),"Listener works when another key remains ready");
 b.edit().putString("tagId","").commit();ok(KeyProfiles.match(c,tag(B),"NEW",true)==null,"Locked NFC refuses unbound tag");
 ok(KeyProfiles.match(c,tag(B),"NEW",false)==b,"Foreground can enroll a new tag");
 KeyProfiles.migrate(c);ok(KeyProfiles.list(c).size()==2,"Migration is idempotent");
 b.edit().putString("tagId","TAG_A").commit();
 ok(KeyProfiles.match(c,tag(A),"TAG_A",true)==a&&KeyProfiles.match(c,tag(B),"TAG_A",true)==b,"Same tag UID still routes by door MAC");
 ok(KeyProfiles.match(c,tag(A),"",false)==null,"Bound tag rejects missing UID");
 a.edit().putLong("lastNfcAt",10000L).commit();
 ok(KeyProfiles.recentTap(a,10001L),"Same door repeat is throttled");
 ok(!KeyProfiles.recentTap(b,10001L),"Other door is not throttled");
 ok(!KeyProfiles.recentTap(a,18000L),"Cooldown expires after eight seconds");
 ok(!KeyProfiles.recentTap(a,9000L),"Clock rollback does not indefinitely block unlocking");
 a.edit().putBoolean("auto",false).commit();ok(!KeyProfiles.anyReady(c),"All disabled keys stop listener eligibility");
 boolean rejected=false;try{KeyProfiles.select(c,"11:22:33:44:55:66");}catch(IllegalArgumentException expected){rejected=true;}
 ok(rejected&&KeyProfiles.current(c)==b,"Unknown default rejected without changing selection");
 ok(c.getSharedPreferences("test-key-"+A,0).getString("value","").equals("00000000000000000000000000000000")&&c.getSharedPreferences("test-key-"+B,0).getString("value","").equals("22222222222222222222222222222222"),"Per-door key updates remain isolated");
 System.out.println(checks+" multi-key routing and migration checks passed (host shims)");
}}
'''
}
java_home=Path(os.environ['JAVA_HOME'])
(root/'build').mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(dir=root/'build',prefix='profile-tests-') as folder:
 folder=Path(folder)
 for name,body in sources.items():
  p=folder/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8')
 files=list(folder.rglob('*.java'))+[root/'src/com/hutao/touchkey/KeyProfiles.java']
 subprocess.run([str(java_home/'bin/javac.exe'),'-encoding','UTF-8','-d',str(folder)]+[str(p) for p in files],check=True)
 subprocess.run([str(java_home/'bin/java.exe'),'-cp',str(folder),'com.hutao.touchkey.KeyProfilesTest'],check=True)
