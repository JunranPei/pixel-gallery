from pathlib import Path
from PIL import Image
import json,re
import numpy as np

root=Path('scratch/viewer-preview-size-20261020')
folder=root/'preview-geometry'
events=[json.loads(line) for line in (folder/'events.jsonl').read_text(encoding='utf-8').splitlines()]
report={'phases':[],'systemThumbnails':[]}
for tap in [e for e in events if e['event']=='TAP']:
    draws=[e for e in events if e['event']=='DRAWABLE' and e['phase']==tap['phase']]
    report['phases'].append(dict(phase=tap['phase'],draws=[dict(afterTapMs=e['uptimeMs']-tap['uptimeMs'],bitmap=e.get('bitmap'),intrinsic=e['intrinsic'],mapped=e['mapped'],matrix=e['matrix'],alpha=e['alpha'],scale=[e['scaleX'],e['scaleY']]) for e in draws]))
for path in sorted(folder.glob('*-system-*.png')):
    with Image.open(path) as im:
        rgb=np.asarray(im.convert('RGB'));lit=rgb.max(axis=2)>30
        columns=np.where(lit.sum(axis=0)>im.height*.3)[0]
        rows=np.where(lit.sum(axis=1)>im.width*.3)[0]
        box=[int(columns.min()),int(rows.min()),int(columns.max()+1),int(rows.max()+1)]
        report['systemThumbnails'].append(dict(name=path.name,size=im.size,contentBox=box))
(root/'geometry-summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
