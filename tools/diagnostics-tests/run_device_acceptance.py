"""Run against a dedicated emulator package already installed by the operator."""
from pathlib import Path
import argparse, json, subprocess, sys, time, statistics

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from viewer_listener import Listener

p=argparse.ArgumentParser()
p.add_argument('--serial',required=True)
p.add_argument('--package',required=True)
p.add_argument('--adb',default='D:/Android_Studio_Sdk/platform-tools/adb.exe')
p.add_argument('--output',required=True)
p.add_argument('--candidate',action='store_true')
p.add_argument('--windows',type=int,default=24)
a=p.parse_args()
assert a.serial.startswith('emulator-'), 'This test only modifies dedicated emulator packages'
assert a.package.startswith('com.pixel.gallery.listener'), 'Dedicated QA package required'
out=Path(a.output);out.mkdir(parents=True,exist_ok=True)
def adb(*args,input=None,check=True):
    r=subprocess.run([a.adb,'-s',a.serial,*args],input=input,capture_output=True,timeout=45)
    if check and r.returncode:raise RuntimeError((r.stderr+r.stdout).decode(errors='replace'))
    return r.stdout
def read(name):return json.loads(adb('exec-out','run-as',a.package,'cat','files/'+name))
def wait(predicate,seconds=180):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            value=read('qa-status.json')
            if value.get('state')=='failed':raise RuntimeError(value['error'])
            if predicate(value):return value
        except json.JSONDecodeError:pass
        except RuntimeError as error:
            if 'No such file' not in str(error):raise
        time.sleep(.4)
    raise TimeoutError('QA instrumentation did not reach requested state')
def command(text,read_result=False):
    adb('shell','run-as',a.package,'sh','-c',"'cat > files/qa-command.tmp'",input=text.encode())
    adb('shell','run-as',a.package,'mv','files/qa-command.tmp','files/qa-command')
    wait(lambda v:v.get('done')==text)
    if read_result:
        name='qa-'+text.split(':',1)[1]+'.json';value=read(name)
        (out/name).write_text(json.dumps(value,indent=2),encoding='utf-8')
        return value
def sample(name):return command('sample:'+name,True)
def zooms(value):
    states=value.get('sample',value)['windows']
    assert len(states)==a.windows and not any(w['destroyed'] for w in states)
    scales=[t['scale']/t['base'] for w in states for t in w['tiles'] if t.get('base')]
    assert len(scales)==a.windows and min(scales)>1.05, 'Every real window must remain zoomed'
    return scales
def invariant(before,after):
    keys=('pid','lifecycle','draws','windows','memory')
    changes={key:[before.get(key),after.get(key)] for key in keys if before.get(key)!=after.get(key)}
    if changes:
        (out/'invariant-failure.json').write_text(json.dumps(changes,indent=2),encoding='utf-8')
        raise AssertionError('Connection/capture changed '+', '.join(changes))
    return {'windows':len(before['windows']),'pid':before['pid'],'unchanged':keys}
for permission in ('READ_MEDIA_IMAGES','READ_MEDIA_VIDEO','POST_NOTIFICATIONS'):
    adb('shell','pm','grant',a.package,'android.permission.'+permission,check=False)
adb('shell','appops','set',a.package,'MANAGE_EXTERNAL_STORAGE','allow')
adb('shell','settings','put','secure','immersive_mode_confirmations','confirmed')
adb('shell','cmd','notification','allow_listener',a.package+'/com.pixel.gallery.services.GalleryNotificationListenerService')
adb('shell','am','force-stop',a.package)
adb('shell','run-as',a.package,'rm','-f','files/qa-status.json','files/qa-command')
log=(out/'instrumentation.txt').open('wb')
process=subprocess.Popen([a.adb,'-s',a.serial,'shell','am','instrument','-w','-e','windows',str(a.windows),a.package+'.test/com.pixel.gallery.integration.ListenerAcceptanceInstrumentation'],stdout=log,stderr=subprocess.STDOUT)
try:
    wait(lambda v:v.get('state')=='ready',seconds=360)
    print('Real viewer windows ready',flush=True)
    result={}
    # Same settling and workload in both builds. No GC commands or forced redraws.
    time.sleep(8)
    result['initialZooms']=zooms(sample('initial'))
    result['offWorkZooms']=zooms(command('exercise:off-work',True))
    time.sleep(5)
    sample('off-1');time.sleep(10);sample('off-2')
    if a.candidate:
        listener=Listener(a.serial,a.package,a.adb)
        before=sample('closed-before')
        try:listener.status()
        except RuntimeError:pass
        else:raise AssertionError('Closed interface responded')
        result['closed']=invariant(before,sample('closed-after'))
        command('open')
        time.sleep(2)
        before=sample('connect-before')
        result['status']=listener.status()
        assert result['status']['interfaceOpen'] and not result['status']['enabled']
        result['connect']=invariant(before,sample('connect-after'))
        before=sample('capture-before')
        folder=listener.collect(out/'captures')
        result['capture']=invariant(before,sample('capture-after'))
        snapshot=json.loads((folder/'snapshot.json').read_text(encoding='utf-8'))
        assert snapshot['live']['supported'] and 'error' not in snapshot['live']
        assert len(snapshot['live']['windows'])>=a.windows
        result['snapshotWindows']=len(snapshot['live']['windows'])
        time.sleep(5)
        sample('connected-1');time.sleep(10);sample('connected-2')
        before=sample('start-before')
        listener.control('start')
        time.sleep(3)
        result['start']=invariant(before,sample('start-after'))
        result['recordingWorkZooms']=zooms(command('exercise:recording-work',True))
        time.sleep(5)
        sample('recording-1');time.sleep(10);sample('recording-2')
        listener.collect(out/'captures')
        before=sample('stop-before')
        listener.control('stop')
        time.sleep(3)
        result['stop']=invariant(before,sample('stop-after'))
        command('close')
        time.sleep(3)
        after=sample('closed-again')
        assert not any(t.startswith('viewer-memory') or t.startswith('viewer-diagnost') for t in after['threads'])
        result['diagnosticThreadsAfterClose']=0
        # Leave UI alive for manual settings and half-sheet checks.
    result['checks']='PASS'
    (out/'result.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result),flush=True)
except BaseException:
    # Preserve the running process, tasks, and failure data for inspection.
    raise
finally:
    log.close()
