"""Reconstruct requests separately from viewer entries, including in-viewer swipes."""
import argparse
import collections
import json
from pathlib import Path
import re
import statistics

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder',type=Path)
    args=parser.parse_args()
    folder=args.folder
    raw=(folder/'continuous-image-events.txt').read_text(encoding='utf-8',errors='replace').splitlines()
    pattern=re.compile(r'^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+\w\s+ViewerLoadMetrics:\s*(.*)')
    entries={};requests={};works={};native_threads={};records=[]
    for line_no,line in enumerate(raw,1):
        m=pattern.match(line)
        if not m:continue
        ts,pid,tid,msg=m.groups();pid=int(pid);tid=int(tid);ts=float(ts)
        fields=dict(re.findall(r'(\w+)=([^\s]+)',msg));kind=msg.split(' ',1)[0]
        record=dict(line=line_no,ts=ts,pid=pid,tid=tid,kind=kind,fields=fields,message=msg);records.append(record)
        if 'entry' not in fields or fields['entry']=='0':continue
        ek=(pid,int(fields['entry']))
        entry=entries.setdefault(ek,dict(pid=pid,entry=ek[1],requests=[]))
        ms=lambda name:float(fields[name].removesuffix('ms'))
        if kind=='ENTRY_REQUEST':entry.update(contentId=fields['contentId'],start=ts)
        if kind=='PREVIEW_START':
            key=(pid,int(fields['request']))
            request=dict(pid=pid,entry=ek[1],request=key[1],contentId=fields.get('id'),key=fields['key'],
                source_dimensions=fields.get('source'),mime=fields.get('mime'),sizeBytes=int(fields.get('bytes',0)),
                viewport=fields.get('view'),model=fields.get('model'),request_ms=ms('sinceClick'),request_ts=ts,
                direct_entry=len(entry['requests'])==0 and fields.get('id')==entry.get('contentId'),entry_start=entry.get('start'))
            requests[key]=request;entry['requests'].append(request)
        request=entry['requests'][-1] if entry['requests'] else None
        if kind in ('PREVIEW_READY','PREVIEW_FAILED','PREVIEW_CLEAR'):
            request=requests.get((pid,int(fields['request'])))
            if request:
                if kind=='PREVIEW_READY':request.update(ready_ms=ms('sinceClick'),preview_ms=ms('duration'),source=fields.get('source'),bitmap=fields.get('bitmap'),ready_ts=ts)
                if kind=='PREVIEW_FAILED':request['failed']=True
                if kind=='PREVIEW_CLEAR':request['cleared_reason']=fields.get('reason')
        if kind=='WORK_START' and fields.get('type')=='NATIVE_JPEG_FIT' and request:
            work=dict(request=request,start_ms=ms('sinceClick'),tid=tid,start_ts=ts)
            works[(pid,int(fields['work']))]=work;native_threads[(pid,tid)]=work
            request.update(native_start_ms=work['start_ms'],native_tid=tid,native_start_ts=ts)
        if kind in ('WORK_READY','WORK_FAILED','WORK_CLEAR') and fields.get('type')=='NATIVE_JPEG_FIT':
            work=works.get((pid,int(fields['work'])))
            if work:
                request=work['request'];request.update(native_end_ms=ms('sinceClick'),native_work_ms=ms('duration'),native_end_ts=ts,native_outcome=kind)
                for key in ('headerMs','allocMs','setupMs','scanMs','finishMs','sample','cpuNs'):
                    if key in fields:request['native_'+key]=float(fields[key])
        if kind=='EVENT' and entry['requests']:
            request=entry['requests'][-1];name=fields.get('name')
            if name in ('NATIVE_FIT_DECODE_DONE','NATIVE_FIT_ROTATE_DONE'):
                work=native_threads.get((pid,tid))
                if work:request=work['request']
            if name=='NATIVE_FIT_DECODE_DONE':request.update(native_decode_ms=int(fields['durationNs'])/1e6,native_cpu_ms=int(fields['cpuNs'])/1e6,native_bitmap=fields.get('bitmap'))
            if name=='NATIVE_FIT_ROTATE_DONE':request['rotate_ms']=int(fields['durationNs'])/1e6
            if name=='FIT_SOURCE_SUBMIT':request.update(submit_ms=ms('sinceClick'),submit_ts=ts)
            if name=='NATIVE_FIT_FALLBACK':request['fallback_ms']=ms('sinceClick')
            if name=='FIT_PREVIEW_PREDRAW':
                matching=[x for x in entry['requests'] if x['key']==fields.get('key') and 'ready_ms' in x]
                if matching:
                    request=matching[-1];request.update(predraw_ms=ms('sinceClick'),predraw_clock_ns=int(fields['clockNanos']))
    result=list(requests.values())
    for request in result:
        for a,b,key in [('native_start_ms','submit_ms','submit_to_native_ms'),('ready_ms','native_end_ms','native_to_ready_ms'),('predraw_ms','ready_ms','ready_to_predraw_ms')]:
            if a in request and b in request:request[key]=round(request[a]-request[b],3)
    summary=dict(entries=len(entries),requests=len(result),completed=sum('ready_ms' in x for x in result),
        sources=dict(collections.Counter(x.get('source') for x in result)),native=sum('native_decode_ms' in x for x in result),
        fallback=sum('fallback_ms' in x for x in result),direct=sum(x['direct_entry'] for x in result),
        region_decodes=sum(x['kind']=='WORK_READY' and x['fields'].get('type')=='PLATFORM_REGION_DECODE' for x in records))
    (folder/'requests-summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    (folder/'request-counts.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
    (folder/'parsed-events.json').write_text(json.dumps(records,ensure_ascii=False),encoding='utf-8')
    print(json.dumps(summary,indent=2))

if __name__=='__main__':main()
