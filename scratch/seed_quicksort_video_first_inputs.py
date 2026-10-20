from pathlib import Path
import subprocess,json

folder=Path(__file__).resolve().parent/'quicksort-video-first-inputs-20261020'
folder.mkdir(exist_ok=True)
ffmpeg='D:/Program Files/FFmpeg/bin/ffmpeg.exe'
probe='D:/Program Files/FFmpeg/bin/ffprobe.exe'
cases=[('h264-no-date.mp4','libx264',None),('h264-date.mp4','libx264','2020-02-03T04:05:06Z'),
       ('h264-fraction.mp4','libx264','2020-02-03T04:05:06.123Z'),('h264-date.mov','libx264','2020-02-03T04:05:06Z'),
       ('hevc-date.mp4','libx265','2020-02-03T04:05:06Z')]
report=[]
for name,codec,date in cases:
    args=[ffmpeg,'-hide_banner','-loglevel','error','-y','-f','lavfi','-i','testsrc2=size=320x240:rate=12','-t','1','-c:v',codec,'-pix_fmt','yuv420p','-threads','1']
    if codec=='libx265':args+=['-x265-params','pools=1:frame-threads=1:log-level=error']
    if date:args+=['-metadata','creation_time='+date]
    subprocess.run(args+[str(folder/name)],check=True)
    data=json.loads(subprocess.check_output([probe,'-v','error','-show_streams','-show_format','-of','json',str(folder/name)]))
    assert any(x.get('codec_type')=='video' for x in data['streams']) and float(data['format']['duration'])>0
    subprocess.run([ffmpeg,'-hide_banner','-loglevel','error','-i',str(folder/name),'-frames:v','1','-f','null','-'],check=True)
    report.append(dict(name=name,codec=codec,creationTime=date,bytes=(folder/name).stat().st_size))
subprocess.run(['D:/Android_Studio_Sdk/platform-tools/adb.exe','push',str(folder),'/sdcard/Download/QuickSortVideoFirstInputs20261020'],check=True)
(folder.parent/'quicksort-video-first-inputs.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report))
