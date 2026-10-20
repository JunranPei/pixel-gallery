from pathlib import Path
from PIL import Image,ImageDraw,ImageOps
import io,struct,subprocess,json,hashlib
root=Path('scratch/viewer-preview-fix-20261020');fixtures=root/'fixtures';fixtures.mkdir(parents=True,exist_ok=True)
def patterned(size,border=False):
    im=Image.new('RGB',size,'black' if border else '#2040b0');d=ImageDraw.Draw(im);w,h=size
    pad=0.1 if border else 0
    d.rectangle((int(w*pad),int(h*pad),int(w*(1-pad))-1,int(h*(1-pad))-1),fill='#e0ac35')
    d.rectangle((int(w*.2),int(h*.2),int(w*.4),int(h*.8)),fill='#2060e0')
    d.ellipse((int(w*.55),int(h*.25),int(w*.85),int(h*.65)),fill='#e05030')
    return im
for name,size in [('landscape',(600,400)),('portrait',(400,600)),('square',(400,400)),('tiny',(32,16)),('panorama',(8000,100))]:
    patterned(size).save(fixtures/(name+'.png'))
def long(tag,value):return struct.pack('<HHII',tag,4,1,value)
def short(tag,value):return struct.pack('<HHIH',tag,3,1,value)+b'\0\0'
for rotation,orientation in [(0,1),(90,6),(180,3),(270,8)]:
    im=patterned((600,400),True);thumb=Image.new('RGB',(160,120),'black');inner=ImageOps.contain(im,(160,120));thumb.paste(inner,((160-inner.width)//2,(120-inner.height)//2))
    buf=io.BytesIO();thumb.save(buf,format='JPEG',quality=95);jpeg=buf.getvalue()
    first=8;second=first+2+3*12+4;data=second+2+5*12+4
    tiff=b'II'+struct.pack('<HI',42,first)+struct.pack('<H',3)+long(256,600)+long(257,400)+short(274,orientation)+struct.pack('<I',second)
    tiff+=struct.pack('<H',5)+long(256,160)+long(257,120)+short(259,6)+long(513,data)+long(514,len(jpeg))+struct.pack('<I',0)+jpeg
    im.save(fixtures/f'black-border-{rotation}.jpg',quality=96,exif=b'Exif\0\0'+tiff)
rows=[]
subprocess.run(['D:/Android_Studio_Sdk/platform-tools/adb.exe','shell','mkdir','-p','/sdcard/Pictures/ThumbnailAspectFix20261020'],check=True)
for p in sorted(fixtures.iterdir()):
    subprocess.run(['D:/Android_Studio_Sdk/platform-tools/adb.exe','push',str(p),'/sdcard/Pictures/ThumbnailAspectFix20261020/'+p.name],check=True,stdout=subprocess.PIPE)
    rows.append(dict(name=p.name,sha256=hashlib.sha256(p.read_bytes()).hexdigest()))
(root/'fixture-manifest.json').write_text(json.dumps(rows,indent=2),encoding='utf-8')
print('Created and pushed',len(rows),'isolated fixtures.')
