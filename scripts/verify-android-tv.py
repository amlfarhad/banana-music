"""Public-preview and remote integration checks on the isolated emulator only."""
from pathlib import Path
import json, os, re, subprocess, time, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
ADB = str(ROOT / '.toolchain/android-sdk/platform-tools/adb')
ENV = dict(os.environ, ANDROID_USER_HOME=str(ROOT / '.toolchain/android-user'))
SERIAL = 'emulator-5556'
def adb(*args):
    return subprocess.check_output([ADB, '-s', SERIAL, *args], env=ENV, text=True, timeout=30)
def keys(*codes):
    adb('shell', 'input', 'keyevent', *map(str, codes))
def tree(name='android-tv-ui.xml'):
    adb('shell', 'uiautomator', 'dump', '/sdcard/aural-check.xml')
    adb('pull', '/sdcard/aural-check.xml', str(ROOT/'evidence'/name))
    return ET.parse(ROOT/'evidence'/name)
def texts(t):
    return [n.get('text','') for n in t.iter('node') if n.get('text')]
def state():
    data=adb('shell','dumpsys','media_session')
    block=data[data.find('AuralTV dev.amal.aural'):]
    match=re.search(r'state=PlaybackState \{state=(\w+)\(\d+\), position=(\d+)',block)
    assert match, 'Aural media session missing'
    return {'state':match[1], 'position_ms':int(match[2])}
def screenshot(name):
    with open(ROOT/'evidence'/name,'wb') as f:
        subprocess.run([ADB,'-s',SERIAL,'exec-out','screencap','-p'],env=ENV,stdout=f,check=True,timeout=30)

assert adb('shell','getprop','ro.kernel.qemu').strip()=='1', 'Refusing to operate on a physical device'
report={'device':adb('shell','getprop','ro.product.model').strip(),'android':adb('shell','getprop','ro.build.version.release').strip()}
adb('shell','am','force-stop','dev.amal.aural')
launch=adb('shell','am','start','-W','-n','dev.amal.aural/.MainActivity')
report['launch']=launch.strip()
time.sleep(4)
screenshot('aural-tv-final.png')
keys(22,22,22,23)
time.sleep(3)
album=tree()
assert 'She’s the Best' in texts(album), 'D-pad album navigation failed'
focus=[n.get('text') for n in album.iter('node') if n.get('focused')=='true']
assert 'Preview' in focus, f'Album focus did not reach playback control: {focus}'
report['nativeAlbumNavigation']='passed'
report['albumFocus']='Preview'
keys(23)
time.sleep(1.6)
first=state();time.sleep(3);second=state()
assert first['state']=='PLAYING' and second['state']=='PLAYING' and second['position_ms']>first['position_ms'], f'Preview did not advance: {first}, {second}'
report['previewSamples']=[first,second]
keys(127);time.sleep(1.3)
paused=state();time.sleep(1.3);paused_again=state()
assert paused['state']=='PAUSED' and paused_again['position_ms']==paused['position_ms'], 'Pause did not hold'
keys(126);time.sleep(2)
assert state()['state']=='PLAYING','Resume failed'
report['pauseResume']='passed'
keys(127)
keys(82);time.sleep(.5);keys(23);time.sleep(.5)
quality=tree('android-tv-quality-ui.xml')
assert 'High — prefer 256 kbps AAC' in texts(quality), 'Native quality settings missing'
screenshot('aural-tv-quality.png')
keys(20,23);time.sleep(.8)
keys(82);time.sleep(.3);keys(23);time.sleep(.5)
low=tree('android-tv-quality-low.xml')
assert any(n.get('text')=='Data saver — prefer 64 kbps AAC' and n.get('checked')=='true' for n in low.iter('node')), 'Data Saver preference did not persist'
keys(19,23);time.sleep(.5)
report['nativeQualitySettings']='passed; Data Saver persisted; restored High'
keys(4);time.sleep(2)
# Select the actual native Search button, avoiding assumptions about focus after Back.
current=tree('android-tv-before-search.xml')
button=next(n for n in current.iter('node') if n.get('class')=='android.widget.Button' and n.get('text')=='Search')
x1,y1,x2,y2=map(int,re.findall(r'\d+',button.get('bounds')))
adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.5)
adb('shell','input','text','Nina%sSimone');keys(66);time.sleep(4)
search=tree('android-tv-search-ui.xml')
assert any('Nina' in s for s in texts(search)), 'Native search did not return the requested artist'
report['nativeSearch']='passed'
errors=adb('logcat','-d','-s','AndroidRuntime:E')
assert 'FATAL EXCEPTION' not in errors, 'Native runtime crash recorded'
report['runtimeCrashCheck']='no AndroidRuntime fatal exception'
report['limitations']=['Public previews only; no subscriber account signed in.','No audible HDMI output check on a headless emulator.','ONN performance and full DRM/subscription playback require an on-device test.']
(ROOT/'evidence/android-tv-verification.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
