from pathlib import Path
from PIL import Image,ImageOps
import numpy as np,json

root=Path('scratch/viewer-preview-fix-20261020')
actual=root/'thumbnail-aspect-checks'
sources=list(Path('D:/Quick Share/新建文件夹 (2)').iterdir())+list((root/'fixtures').iterdir())
rows=[]
for source in sources:
    if not source.is_file():continue
    for size in (200,512):
        with Image.open(actual/f'{source.name}-{size}.png') as im:
            dimensions=im.size
            result=im.convert('RGB').resize((128,128),Image.Resampling.BILINEAR)
        # Compare equal spatial information, including thumbnails only 4-7 pixels tall.
        with Image.open(source) as im:
            reference=ImageOps.exif_transpose(im).convert('RGB').resize(dimensions,Image.Resampling.BILINEAR).resize((128,128),Image.Resampling.BILINEAR)
        a=np.asarray(reference,dtype=np.float64);b=np.asarray(result,dtype=np.float64)
        mae=float(np.abs(a-b).mean());correlation=float(np.corrcoef(a.flatten(),b.flatten())[0,1])
        minimumCorrelation=.96 if min(dimensions)<=16 else .98
        rows.append(dict(file=source.name,size=size,dimensions=dimensions,mae=mae,correlation=correlation,minimumCorrelation=minimumCorrelation))
        assert correlation>minimumCorrelation and mae<15,(source.name,size,mae,correlation)
(root/'pixel-comparison.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
print('32 full-frame pixel comparisons passed. Minimum correlation:',min(row['correlation'] for row in rows),'Maximum MAE:',max(row['mae'] for row in rows))
