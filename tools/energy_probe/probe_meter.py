import argparse
import hashlib
import json
from pathlib import Path
import time
from emi import WindowsMeter
from analysis import energy_window, subtract_bracket

parser=argparse.ArgumentParser()
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
args.output.mkdir(parents=True,exist_ok=False)
with WindowsMeter() as meter:
    windows=[]
    def collect(name,seconds,busy=False):
        samples=[meter.snapshot()];deadline=time.monotonic()+seconds
        next_sample=time.monotonic()+.25
        blob=b'x'*32768
        while time.monotonic()<deadline:
            if busy:
                blob=hashlib.sha256(blob).digest()*1024
            else: time.sleep(.01)
            if time.monotonic()>=next_sample:
                samples.append(meter.snapshot());next_sample+=.25
        samples.append(meter.snapshot())
        result={'name':name,'samples':samples,**energy_window(samples[0],samples[-1],meter.package_names[0])}
        windows.append(result)
        print(name,round(result['average_power_w'],3),'W',flush=True)
    collect('idle-before',4)
    collect('one-thread-controlled-hash',6,True)
    collect('idle-after',4)
    result={'meter':meter.describe(),'windows':windows,'controlled_load_difference':subtract_bracket(*windows)}
    (args.output/'probe.json').write_text(json.dumps(result,indent=2))
    print(result['controlled_load_difference'],flush=True)
