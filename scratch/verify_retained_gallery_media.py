from pathlib import Path
import hashlib, io, json, subprocess, tarfile

base=Path(__file__).resolve().parent
original=base/'gallery-regression-media-20261020'
expected={p.relative_to(original).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in original.rglob('*') if p.is_file()}
assert len(expected)==318, len(expected)
raw=subprocess.check_output(['D:/Android_Studio_Sdk/platform-tools/adb.exe','exec-out','tar','-C','/sdcard/Pictures/GalleryQA20261020','-cf','-','.'])
actual={}
with tarfile.open(fileobj=io.BytesIO(raw),mode='r:') as archive:
    for member in archive:
        if member.isfile():
            name=member.name.removeprefix('./')
            actual[name]=hashlib.sha256(archive.extractfile(member).read()).hexdigest()
differences=[name for name in expected.keys()|actual.keys() if expected.get(name)!=actual.get(name)]
result=dict(retained_sample_files=len(actual),sha256_mismatches=differences)
(base/'copy-move-retained-media-verification.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
assert not differences,result
print(json.dumps(result))
