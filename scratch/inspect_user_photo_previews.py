from pathlib import Path
from PIL import Image
import struct,io,json,hashlib

source=Path('D:/Quick Share/新建文件夹 (2)')
output=Path('scratch/viewer-preview-size-20261020');output.mkdir(exist_ok=True)
report=[]
for path in source.iterdir():
    if not path.is_file():continue
    raw=path.read_bytes();item=dict(name=path.name,bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest())
    with Image.open(path) as image:
        exif=image.getexif();item.update(size=image.size,orientation=exif.get(274),camera=exif.get(272),dpi=str(image.info.get('dpi')),format=image.format,frames=getattr(image,'n_frames',1))
        tiff=image.info.get('exif',b'')[6:]
    if tiff[:2] in (b'II',b'MM'):
        order='<' if tiff[:2]==b'II' else '>'
        u16=lambda at:struct.unpack_from(order+'H',tiff,at)[0]
        u32=lambda at:struct.unpack_from(order+'I',tiff,at)[0]
        ifd0=u32(4);ifd1=u32(ifd0+2+12*u16(ifd0))
        if ifd1:
            tags={u16(ifd1+2+12*i):u32(ifd1+2+12*i+8) for i in range(u16(ifd1))}
            start=tags.get(513);size=tags.get(514)
            if start is not None and size:
                thumb=tiff[start:start+size]
                (output/(path.stem+'-embedded.jpg')).write_bytes(thumb)
                with Image.open(io.BytesIO(thumb)) as im:
                    rgb=im.convert('RGB');w,h=im.size
                    def lit(x,y):return max(rgb.getpixel((x,y)))>30
                    columns=[x for x in range(w) if sum(lit(x,y) for y in range(h))>h*.3]
                    rows=[y for y in range(h) if sum(lit(x,y) for x in range(w))>w*.3]
                    item['thumbnail']=dict(size=im.size,orientation=tags.get(274),contentBox=[min(columns),min(rows),max(columns)+1,max(rows)+1] if columns and rows else None)
    report.append(item)
(output/'photo-metadata.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
