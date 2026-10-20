from pathlib import Path
import subprocess,re,json

base=Path(__file__).resolve().parent
adb='D:/Android_Studio_Sdk/platform-tools/adb.exe'
summary=[]
for run in range(1,6):
    result=subprocess.check_output([adb,'shell','am','instrument','-w','-e','suite','video-first-attempt','-e','edgeFilter','queue',
        'com.pixel.gallery.multitask.test.test/com.pixel.gallery.integration.QuickSortSafetyInstrumentation'])
    text=result.decode('utf-8-sig')
    (base/f'quicksort-video-first-cold-{run}.log').write_text(text,encoding='utf-8')
    assert 'checks passed' in text and 'FAIL ' not in text,text
    passed=len(re.findall(r'^PASS ',text,re.M));assert passed==5,passed
    pid=int(re.search(r'INFO process=(\d+)',text).group(1))
    summary.append(dict(run=run,pid=pid,firstAttemptVideoCases=passed))
    print(json.dumps(summary[-1]),flush=True)
assert len({x['pid'] for x in summary})==5,summary
(base/'quicksort-video-first-cold-results.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
