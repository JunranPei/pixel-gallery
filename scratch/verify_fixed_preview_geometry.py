from pathlib import Path
import json,re

root=Path('scratch/viewer-preview-fix-20261020')
report={}
for variant,folder in [('trace','preview-geometry-fixed'),('main','preview-geometry-mainfixed')]:
    events=[json.loads(line) for line in (root/variant/folder/'events.jsonl').read_text(encoding='utf-8').splitlines()]
    groups=[]
    for tap in [event for event in events if event['event']=='TAP']:
        draws=[event for event in events if event['event']=='DRAWABLE' and event['phase']==tap['phase']]
        assert draws,tap['phase']
        final=draws[-1]
        assert final['bitmap'] in ['1080x1620','1080x1619'],final
        finalBounds=[float(value) for value in re.findall(r'-?\d+\.?\d*',final['mapped'])]
        maximumError=0
        for draw in draws:
            assert draw['bitmap']!='120x160',draw
            bounds=[float(value) for value in re.findall(r'-?\d+\.?\d*',draw['mapped'])]
            error=max(abs(a-b) for a,b in zip(bounds,finalBounds))
            assert error<=3,draw
            maximumError=max(maximumError,error)
        groups.append(dict(phase=tap['phase'],bitmaps=[draw['bitmap'] for draw in draws],maximumEdgeDifferencePx=maximumError))
    assert len(groups)==14
    report[variant]=groups
(root/'fixed-geometry-acceptance.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print('28 real viewer opens passed. Maximum intermediate/final edge difference:',max(case['maximumEdgeDifferencePx'] for cases in report.values() for case in cases),'pixels')
