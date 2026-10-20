from pathlib import Path
import subprocess,hashlib,json,time,shlex,os

adb='D:/Android_Studio_Sdk/platform-tools/adb.exe'
root=Path(os.environ.get('PREVIEW_QA_OUTPUT','scratch/viewer-preview-size-20261020'))
root.mkdir(parents=True,exist_ok=True)
package=os.environ.get('PREVIEW_QA_PACKAGE','com.pixel.gallery.codextrace')

def run(*args,**kwargs):
    return subprocess.run([adb,*args],check=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,**kwargs).stdout

def prepare():
    source=Path('D:/Quick Share/新建文件夹 (2)')
    target='/sdcard/Pictures/PreviewSizeQA_20261020/'
    run('shell','mkdir','-p',target)
    rows=[]
    for path in sorted(source.iterdir()):
        if not path.is_file():continue
        run('push',str(path),target+path.name)
        remote=run('exec-out','cat '+shlex.quote(target+path.name))
        original=path.read_bytes()
        assert original==remote,path.name
        rows.append(dict(name=path.name,bytes=len(remote),sha256=hashlib.sha256(remote).hexdigest()))
    (root/'device-copies.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Seven copies pushed and verified byte for byte.',flush=True)

def collect():
    (root/'recording-start.json').write_text(json.dumps(dict(hostTime=time.time(),deviceUptime=run('shell','cat','/proc/uptime').decode().strip())),encoding='utf-8')
    logfile=(root/'load-logcat.txt').open('wb')
    logproc=subprocess.Popen([adb,'logcat','-v','threadtime','-T','1','ViewerLoadMetrics:I','PreviewGeometryQA:I','GalleryLifecycle:E','AndroidRuntime:E','*:S'],stdout=logfile,stderr=subprocess.STDOUT)
    recording='/sdcard/preview-size-20261020.mp4'
    vidproc=subprocess.Popen([adb,'shell','screenrecord','--bit-rate','12000000','--time-limit','150',recording],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    try:
        time.sleep(1)
        result=run('shell','am','instrument','-w','-e','suite','viewer-preview-geometry','-e','geometryRun',os.environ.get('PREVIEW_QA_RUN','baseline'),package+'.test/com.pixel.gallery.integration.QuickSortSafetyInstrumentation')
        (root/'instrumentation-result.txt').write_bytes(result)
        print(result.decode('utf-8','replace'),flush=True)
    finally:
        # Only one recorder is launched by this collector; avoid terminating unrelated processes.
        pids=run('shell','pidof','screenrecord').decode().split() if vidproc.poll() is None else []
        if len(pids)==1:run('shell','kill','-2',pids[0])
        vidproc.wait(timeout=20)
        logproc.terminate();logproc.wait(timeout=10);logfile.close()
        run('pull',recording,str(root/'loading.mp4'))
        label=os.environ.get('PREVIEW_QA_RUN','baseline')
        folder='preview-geometry' if label=='baseline' else 'preview-geometry-'+label
        run('pull','/sdcard/Android/data/'+package+'/files/'+folder,str(root))

if __name__=='__main__':
    import sys
    if sys.argv[-1]=='prepare':prepare()
    elif sys.argv[-1]=='collect':collect()
