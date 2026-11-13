"""Run reproducible emulator interactions while reading host package EMI energy.

All app changes are temporary preference changes in the isolated clean package.
Raw inputs and bracketing idle windows are retained; phone calibration stays explicit.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import threading
import time

import psutil
from analysis import energy_window, subtract_bracket
from emi import WindowsMeter
from preferences import string_preference
import emulator_ui as ui


class Sampling:
    def __init__(self, meter): self.meter=meter;self.stop=threading.Event();self.samples=[];self.errors=[]
    def __enter__(self):
        self.samples.append(self.meter.snapshot())
        def run():
            while not self.stop.wait(.5):
                try: self.samples.append(self.meter.snapshot())
                except Exception as exc: self.errors.append(repr(exc));return
        self.thread=threading.Thread(target=run,daemon=True);self.thread.start();return self
    def __exit__(self,*args):
        self.stop.set();self.thread.join();self.samples.append(self.meter.snapshot())
        if self.errors: raise RuntimeError(self.errors)


def host_cpu(emulator):
    t=psutil.cpu_times();q=emulator.cpu_times()
    return {'busy_s':t.user+t.system,'emulator_process_s':q.user+q.system}


def write_json(path, value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')


def photo_top():
    ui.home();ui.click('Photos');time.sleep(1)
    # Backward flings rather than querying/recording while the measured action runs.
    for _ in range(14):
        rows=ui.labels()
        date=next((b for t,d,b in rows if t=='November 13, 2026'),None)
        if date and 235<=int(re.findall(r'\d+',date)[1])<=400:
            return int(re.findall(r'\d+',date)[3])+169
        ui.swipe(540,450,540,2100,130);time.sleep(.8)
    raise RuntimeError('Owned top fixtures not reached')


def measure(out, name, seconds, action, meter, emulator):
    ui.shell('dumpsys gfxinfo '+ui.PKG+' reset')
    guest0=ui.cpu();host0=host_cpu(emulator)
    events=[]
    with Sampling(meter) as sampling:
        started=time.monotonic()
        action(events, started)
        time.sleep(max(0,seconds-(time.monotonic()-started)))
    host1=host_cpu(emulator);guest1=ui.cpu()
    if guest0['pid']!=guest1['pid']: raise RuntimeError('Target process restarted during measurement')
    frames,gfx=ui.gfx(name)
    window=energy_window(sampling.samples[0],sampling.samples[-1],meter.package_names[0])
    result={'name':name,**window,'guest_cpu_ms':guest1['cpu_ms']-guest0['cpu_ms'],
            'host_busy_cpu_s':host1['busy_s']-host0['busy_s'],
            'emulator_process_cpu_s':host1['emulator_process_s']-host0['emulator_process_s'],
            'gfxinfo_frames':frames,'events':events,'samples':sampling.samples}
    write_json(out/(name+'.json'),result)
    print(json.dumps({'window':name,'host_w':round(window['average_power_w'],3),'guest_cpu_ms':result['guest_cpu_ms'],'frames':frames}),flush=True)
    return result


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--repetitions',type=int,default=3)
    parser.add_argument('--baseline-seconds',type=float,default=6)
    parser.add_argument('--work-seconds',type=float,default=18)
    parser.add_argument('--settle-seconds',type=float,default=8)
    parser.add_argument('--initial-settle-seconds',type=float,default=30)
    parser.add_argument('--resume',action='store_true')
    parser.add_argument('--cases',nargs='+',default=['menu-held','opening-none','opening-fade','opening-thumbnail','scroll-loop','navigation-loop'])
    args=parser.parse_args()
    if args.repetitions<1 or args.baseline_seconds<2 or args.work_seconds<18:
        parser.error('At least one repetition, 2s baselines and 18s work windows required')
    out=args.output.resolve()
    if out.exists() and any(out.iterdir()) and not args.resume: parser.error('Output directory contains existing evidence; use --resume')
    if args.resume and not (out/'blocks.json').exists(): parser.error('Resume requires previously completed blocks')
    out.mkdir(parents=True,exist_ok=True)
    ui.OUT=out/'ui';ui.OUT.mkdir(exist_ok=True)
    emulator=next((p for p in psutil.process_iter(['name']) if (p.info['name'] or '').lower().startswith('qemu-system-x86_64')),None)
    if emulator is None: parser.error('No running qemu-system-x86_64 emulator process')
    before=ui.adb('exec-out','run-as',ui.PKG,'cat','files/datastore/settings.preferences_pb')
    backup=out/('settings-before-resume-'+str(time.time_ns())+'.pb' if args.resume else 'settings-before.pb')
    backup.write_bytes(before)
    apk_path=ui.shell('pm path '+ui.PKG).strip().split(':',1)[1]
    metadata={'started_utc':datetime.now(timezone.utc).isoformat(),
        'arguments':{**vars(args),'output':str(out)},'package':ui.PKG,
        'apk_sha256':ui.shell('sha256sum '+apk_path).split()[0],
        'emulator_pid':emulator.pid,'host_cpu':psutil.cpu_count(),
        'phone_estimate_calibrated':False,'phone_energy_j':None,
        'host_scope':'Windows EMI RAPL_Package0_PKG, package only, subdomains not added',
        'sampling_interval_s':.5,'block_order':'balanced opening order and interleaved static/scroll/navigation cases'}
    blocks=json.loads((out/'blocks.json').read_text(encoding='utf-8')) if args.resume else []
    done={(b['rep'],b['case']) for b in blocks}
    mode_labels={'opening-none':'None','opening-fade':'Scale & fade','opening-thumbnail':'Thumbnail'}
    try:
        with WindowsMeter() as meter:
            metadata['meter']=meter.describe();metadata['settings_backup']=backup.name
            if args.resume:
                prior=json.loads((out/'metadata.json').read_text(encoding='utf-8'))
                if prior['apk_sha256']!=metadata['apk_sha256']:raise RuntimeError('Cannot merge measurements from different APKs')
                write_json(out/('metadata-resume-'+str(time.time_ns())+'.json'),metadata)
            else:write_json(out/'metadata.json',metadata)
            print('INITIAL SETTLE',args.initial_settle_seconds,flush=True)
            time.sleep(args.initial_settle_seconds)
            for rep in range(args.repetitions):
                balanced=[['menu-held','opening-none','scroll-loop','opening-fade','navigation-loop','opening-thumbnail'],
                          ['opening-fade','navigation-loop','opening-thumbnail','menu-held','opening-none','scroll-loop'],
                          ['opening-thumbnail','scroll-loop','opening-none','navigation-loop','opening-fade','menu-held']]
                if set(args.cases)==set(balanced[0]): order=balanced[rep%3]
                else: order=args.cases[rep%len(args.cases):]+args.cases[:rep%len(args.cases)]
                for case in order:
                    if (rep+1,case) in done:continue
                    print(f'PREPARE {rep+1} {case}',flush=True)
                    y=photo_top()
                    if case in mode_labels:
                        ui.at_display();ui.click(mode_labels[case]);ui.home();y=photo_top()
                        # Exclude initial cache/JIT activity, then wait for delayed toolbar changes.
                        for warm in range(2):
                            ui.tap(674,y);time.sleep(.75)
                            if warm==0:
                                ui.tap(750,2232);time.sleep(.6)
                                labels=ui.labels()
                                if not any(t=='portrait.jpg' for t,d,bounds in labels):
                                    raise RuntimeError('Opening warm-up did not show the owned portrait details')
                                write_json(out/f'{rep+1:02d}-{case}-viewer-check.json',labels)
                                ui.back();time.sleep(.8)
                            ui.back();time.sleep(.9)
                        payload=ui.adb('exec-out','run-as',ui.PKG,'cat','files/datastore/settings.preferences_pb')
                        chosen=string_preference(payload,'viewer_opening_effect')
                        wanted={'opening-none':'NONE','opening-fade':'SCALE_FADE','opening-thumbnail':'THUMBNAIL'}[case]
                        if chosen!=wanted: raise RuntimeError(f'Effect mismatch: expected {wanted}, found {chosen}')
                        write_json(out/f'{rep+1:02d}-{case}-preference.json',{'expected':wanted,'actual':chosen})
                    elif case=='navigation-loop':
                        ui.click('Albums');time.sleep(1);ui.click('Photos');time.sleep(1)
                    time.sleep(args.settle_seconds)
                    prefix=f'{rep+1:02d}-{case}'
                    b=measure(out,prefix+'-baseline-before',args.baseline_seconds,lambda e,s:None,meter,emulator)
                    if case=='menu-held':
                        ui.click('More');time.sleep(2)
                        if not any(t=='Settings' for t,d,bounds in ui.labels()): raise RuntimeError('Menu not open')
                        action=lambda e,s:None
                    else:
                        def action(events,started,case=case,y=y):
                            for i in range(6):
                                when=time.monotonic()-started
                                if case in mode_labels:
                                    ui.tap(674,y);time.sleep(.75);ui.back();time.sleep(.65)
                                elif case=='scroll-loop':
                                    ui.swipe(550,1750,550,600,250);time.sleep(.55)
                                    ui.swipe(550,600,550,1750,250);time.sleep(.55)
                                elif case=='navigation-loop':
                                    ui.tap(700,2210);time.sleep(.8);ui.tap(365,2210);time.sleep(.8)
                                else: raise ValueError('Unknown case '+case)
                                events.append({'cycle':i+1,'start_s':when,'end_s':time.monotonic()-started})
                                time.sleep(max(0,3*(i+1)-(time.monotonic()-started)))
                    w=measure(out,prefix+'-work',args.work_seconds,action,meter,emulator)
                    labels=ui.labels();ui.shot(prefix+'-final')
                    if case=='menu-held': ui.back()
                    elif not any(t=='Pixel Gallery' for t,d,bounds in labels): raise RuntimeError('Workload did not return to home')
                    time.sleep(args.settle_seconds)
                    a=measure(out,prefix+'-baseline-after',args.baseline_seconds,lambda e,s:None,meter,emulator)
                    diff=subtract_bracket(b,w,a)
                    diff['baseline_drift_sensitive']=abs(diff['baseline_drift_w'])>max(1.0,diff['baseline_power_w']*.10)
                    # Guest CPU uses the same time-weighted bracketing baseline as energy.
                    weight=(w['midpoint_s']-b['midpoint_s'])/(a['midpoint_s']-b['midpoint_s'])
                    cpu_rate=(b['guest_cpu_ms']/b['host_seconds']*(1-weight)+a['guest_cpu_ms']/a['host_seconds']*weight)
                    diff['incremental_guest_cpu_ms']=w['guest_cpu_ms']-cpu_rate*w['host_seconds']
                    block={'rep':rep+1,'case':case,'before':b['name'],'work':w['name'],'after':a['name'],
                           'work_host_seconds':w['host_seconds'],'cycles':len(w['events']),**diff}
                    if block['cycles']: block['incremental_host_j_per_cycle']=diff['incremental_energy_j']/block['cycles']
                    blocks.append(block);write_json(out/'blocks.json',blocks)
                    print('BLOCK '+json.dumps(block),flush=True)
    finally:
        # Stop the writer before restoring exact original preferences.
        ui.shell('am force-stop '+ui.PKG)
        subprocess.run(['adb','-s',ui.SERIAL,'shell',"run-as "+ui.PKG+" sh -c 'cat > files/datastore/settings.preferences_pb'"],input=before,check=True)
        restored=ui.adb('exec-out','run-as',ui.PKG,'cat','files/datastore/settings.preferences_pb')
        write_json(out/'restore.json',{'identical':restored==before,'sha256':hashlib.sha256(restored).hexdigest()})
        if restored!=before: raise RuntimeError('Settings restoration verification failed')
        ui.shell('am start -n '+ui.PKG+'/com.pixel.gallery.MainActivity');time.sleep(5)
        for _ in range(3):
            if any(t=='Cancel' for t,d,bounds in ui.labels()): ui.click('Cancel')
            else: break


if __name__=='__main__': main()
