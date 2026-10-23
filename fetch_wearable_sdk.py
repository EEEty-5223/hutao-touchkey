"""Download the SDK linked by Xiaomi's official interconnect documentation.
No vendor signing keys, demo app, or device data is imported into this project.
"""
from pathlib import Path
import urllib.request, zipfile, io, hashlib

URL='https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip'
dest=Path(__file__).resolve().parent.parent/'android-tools'/'vendor'/'xiaomi-wearable-1.4.jar'
data=urllib.request.urlopen(URL,timeout=60).read()
demo=zipfile.ZipFile(io.BytesIO(data))
aar=zipfile.ZipFile(io.BytesIO(demo.read('interconnect_dev_test_demo/libs/xms-wearable-lib_1.4_release.aar')))
jar=aar.read('classes.jar')
expected='0fda1b23e2d87f02041e1d19f7ee8f872c762a4b4eff0dc613fd9c1358526d22'
if hashlib.sha256(jar).hexdigest()!=expected:
    raise SystemExit('Official SDK content changed; review the new SDK before building.')
dest.parent.mkdir(parents=True,exist_ok=True)
dest.write_bytes(jar)
print('SDK ready:',dest,'SHA256',hashlib.sha256(jar).hexdigest())
