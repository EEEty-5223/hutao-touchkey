from pathlib import Path
import subprocess, re, shutil, zipfile, secrets, os, json, argparse, xml.etree.ElementTree as ET

parser=argparse.ArgumentParser()
parser.add_argument('--release',action='store_true')
args=parser.parse_args()

ROOT=Path(__file__).resolve().parent
TOOLS=ROOT.parent/'android-tools'
SDK=TOOLS/'sdk'
BUILD=ROOT/'build'
BUILD.mkdir(exist_ok=True)
JAVA=Path(r'C:\Program Files\Common Files\Oracle\Java\javapath\java.exe')
props=subprocess.run([str(JAVA),'-XshowSettings:properties','-version'],capture_output=True,text=True).stderr
JAVA_HOME=Path(re.search(r'java.home\s*=\s*(.+)',props).group(1).strip())
BIN=JAVA_HOME/'bin'
BT=SDK/'android-16'
ANDROID=SDK/'android-36'/'android.jar'
WEARABLE=TOOLS/'vendor'/'xiaomi-wearable-1.4.jar'
if not WEARABLE.exists():
    raise SystemExit('Run python fetch_wearable_sdk.py to download the official Xiaomi wearable SDK first.')

def run(args):
    subprocess.run([str(a) for a in args],check=True,cwd=ROOT)

suffix='-release' if args.release else ''
GEN=BUILD/('gen'+suffix);CLASSES=BUILD/('classes'+suffix);DEX=BUILD/('dex'+suffix)
for folder in [GEN,CLASSES,DEX]:folder.mkdir(exist_ok=True)
manifest=ROOT/'AndroidManifest.xml'
apk=BUILD/('hutao-touchkey.apk' if args.release else 'hutao-touchkey-test.apk')
if args.release:
    ET.register_namespace('android','http://schemas.android.com/apk/res/android')
    ns='{http://schemas.android.com/apk/res/android}'
    tree=ET.parse(manifest);root=tree.getroot()
    root.set(ns+'versionCode',str(int(root.get(ns+'versionCode'))+1))
    root.set(ns+'versionName',root.get(ns+'versionName','1.0'))
    root.find('application').set(ns+'debuggable','false')
    manifest=BUILD/'AndroidManifest-release.xml';tree.write(manifest,encoding='utf-8',xml_declaration=True)
run([BT/'aapt2.exe','compile','--dir',ROOT/'res','-o',BUILD/'resources.zip'])
run([BT/'aapt2.exe','link','-I',ANDROID,'--manifest',manifest,'--java',GEN,'-o',BUILD/'resources.apk',BUILD/'resources.zip'])
sources=list((ROOT/'src').rglob('*.java'))+list(GEN.rglob('*.java'))
run([BIN/'javac.exe','-J-Duser.language=en','-encoding','UTF-8','--release','8','-classpath',str(ANDROID)+os.pathsep+str(WEARABLE),'-d',CLASSES]+sources)
classes=list(CLASSES.rglob('*.class'))
run([JAVA,'-cp',BT/'lib'/'d8.jar','com.android.tools.r8.D8','--lib',ANDROID,'--min-api','26','--output',DEX,WEARABLE]+classes)
unsigned=BUILD/'unsigned.apk'
shutil.copy2(BUILD/'resources.apk',unsigned)
with zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED) as z:
    z.write(DEX/'classes.dex','classes.dex')
run([BT/'zipalign.exe','-f','4',unsigned,BUILD/'aligned.apk'])
signing=TOOLS/'signing';signing.mkdir(exist_ok=True)
settings=signing/'touchkey-signing.json'
if settings.exists():config=json.loads(settings.read_text())
else:
    config={'password':secrets.token_urlsafe(24)}
    settings.write_text(json.dumps(config))
    os.environ['TOUCHKEY_SIGNING_PASSWORD']=config['password']
    run([BIN/'keytool.exe','-genkeypair','-keystore',signing/'touchkey.p12','-storetype','PKCS12','-storepass:env','TOUCHKEY_SIGNING_PASSWORD','-alias','touchkey','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Hutao Personal Touch Key'])
os.environ['TOUCHKEY_SIGNING_PASSWORD']=config['password']
run([JAVA,'-jar',BT/'lib'/'apksigner.jar','sign','--ks',signing/'touchkey.p12','--ks-pass','env:TOUCHKEY_SIGNING_PASSWORD','--ks-key-alias','touchkey','--out',apk,BUILD/'aligned.apk'])
run([JAVA,'-jar',BT/'lib'/'apksigner.jar','verify','--verbose',apk])
print('APK_READY',apk)
