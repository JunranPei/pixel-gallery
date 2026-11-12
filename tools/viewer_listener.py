"""Reusable desktop listener for the Gallery diagnostic interface; never installs APKs."""
from pathlib import Path
import argparse
import datetime
import json
import re
import subprocess
import threading
import base64
import uuid
import os

DEFAULT_ADB = 'D:/Android_Studio_Sdk/platform-tools/adb.exe'
DEFAULT_PACKAGE = 'com.pixel.gallery.multitask'

class Listener:
    def __init__(self, serial, package=DEFAULT_PACKAGE, adb=DEFAULT_ADB):
        if not re.fullmatch(r'[A-Za-z0-9_.]+', package):
            raise ValueError('Invalid application package')
        if not serial:
            raise ValueError('Select a device')
        self.adb, self.serial, self.package = adb, serial, package
        self.pid = None

    def run(self, *args):
        result = subprocess.run([self.adb, '-s', self.serial, *args], capture_output=True, timeout=30,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        if result.returncode:
            raise RuntimeError((result.stderr+result.stdout).decode('utf-8',errors='replace').strip())
        return result.stdout

    def request(self, command, **extras):
        nonce=uuid.uuid4().hex
        # REGISTERED_ONLY prevents Android from starting the app to receive this request.
        args=['shell','am','broadcast','-p',self.package,'-a',self.package+'.VIEWER_DIAGNOSTICS_CONTROL',
              '-f','0x40000000','--es','command',command,'--es','request_id',nonce]
        for key,value in extras.items():args += ['--el' if isinstance(value,int) else '--es',key,str(value)]
        raw=self.run(*args)
        match=re.search(rb'GALLERY_DIAG_V2:([A-Za-z0-9+/=]+)',raw)
        if not match:
            raise RuntimeError('现有进程的接口未响应；没有启动或刷新应用。')
        try:
            value=json.loads(base64.b64decode(match[1]))
        except (ValueError, UnicodeDecodeError):
            raise RuntimeError('诊断响应格式不正确')
        if value.get('protocol') != 2 or value.get('package') != self.package or value.get('requestId')!=nonce:
            raise RuntimeError('诊断接口版本或应用身份不匹配')
        if self.pid is not None and value.get('pid')!=self.pid:
            raise RuntimeError('应用进程已经变化，未继续读取新进程。')
        self.pid=value.get('pid')
        return value

    def read(self, resource):
        parts=[];offset=0
        while True:
            value=self.request('read',resource=resource,offset=offset)
            data=base64.b64decode(value['data'],validate=True)
            if value['next']!=offset+len(data):raise RuntimeError('记录分块位置不一致')
            parts.append(data);offset=value['next']
            if value['eof']:return b''.join(parts)
            if not data:raise RuntimeError('记录传输没有进展')

    def status(self):return self.request('status')

    def control(self, method):
        if method not in ('start','stop','capture'):
            raise ValueError('Unsupported command')
        value = self.request(method)
        if method in ('start','stop') and value['enabled'] != (method=='start'):
            raise RuntimeError('应用没有应用本次记录开关')
        return value

    def collect(self, destination):
        status = self.control('capture')
        raw = self.read('snapshot.json')
        try: snapshot = json.loads(raw)
        except (ValueError, UnicodeDecodeError): raise RuntimeError('记忆快照读取失败')
        if not isinstance(snapshot.get('viewerMemory'),dict):
            raise RuntimeError('记忆快照格式不正确')
        out = Path(destination)/datetime.datetime.now().strftime('capture-%Y%m%d-%H%M%S-%f')
        out.mkdir(parents=True)
        (out/'snapshot.json').write_bytes(raw)
        manifest = dict(serial=self.serial, package=self.package, status=status, logs={})
        for name in ('previous','current'):
            try:raw = self.read(name+'.log')
            except RuntimeError as error:
                manifest['logs'][name]=dict(unavailable=str(error));continue
            # adb exec-out may return status 0 even for a provider error.
            if b'EVENT name=' in raw:
                (out/(name+'.log')).write_bytes(raw)
                manifest['logs'][name]=dict(bytes=len(raw))
            else:
                manifest['logs'][name]=dict(unavailable=raw.decode('utf-8',errors='replace')[:300])
        (out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
        return out

def devices(adb=DEFAULT_ADB):
    data = subprocess.check_output([adb,'devices'],creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0).decode('utf-8',errors='replace')
    return [l.split()[0] for l in data.splitlines()[1:] if len(l.split())>=2 and l.split()[1]=='device']

def gui():
    import tkinter as tk
    from tkinter import ttk, filedialog
    window = tk.Tk(); window.title('相册诊断监听器'); window.geometry('620x340')
    frame=ttk.Frame(window,padding=16); frame.pack(fill='both',expand=True)
    serial=tk.StringVar(); package=tk.StringVar(value=DEFAULT_PACKAGE)
    output=tk.StringVar(value=str(Path.home()/'Documents'/'GalleryDiagnostics'))
    message=tk.StringVar(value='选择设备并连接。连接不会开启记录，也不会安装应用。')
    connection={}
    ttk.Label(frame,text='设备').grid(row=0,column=0,sticky='w',pady=5)
    device_box=ttk.Combobox(frame,textvariable=serial,state='readonly',width=43); device_box.grid(row=0,column=1,sticky='ew')
    def refresh():
        try:
            values=devices(); device_box['values']=values
            if values and serial.get() not in values: serial.set(values[0])
            if not values: message.set('未发现已授权的设备，请连接 USB 或先完成无线调试连接。')
        except Exception as e: message.set(str(e))
    ttk.Button(frame,text='刷新设备',command=refresh).grid(row=0,column=2,padx=6)
    ttk.Label(frame,text='应用').grid(row=1,column=0,sticky='w',pady=5)
    ttk.Entry(frame,textvariable=package).grid(row=1,column=1,columnspan=2,sticky='ew')
    ttk.Label(frame,text='保存到').grid(row=2,column=0,sticky='w',pady=5)
    ttk.Entry(frame,textvariable=output).grid(row=2,column=1,sticky='ew')
    def choose():
        value=filedialog.askdirectory()
        if value: output.set(value)
    ttk.Button(frame,text='选择目录',command=choose).grid(row=2,column=2,padx=6)
    buttons=[]
    def operation(action):
        selected_serial,selected_package,selected_output=serial.get(),package.get(),output.get()
        for button in buttons: button.configure(state='disabled')
        message.set('正在与应用通信…')
        def work():
            try:
                identity=(selected_serial,selected_package)
                if action=='status':
                    client=Listener(selected_serial,selected_package)
                    connection.clear();connection.update(identity=identity,client=client)
                elif connection.get('identity')!=identity:
                    raise RuntimeError('请先连接当前选择的设备和应用。')
                else:client=connection['client']
                if action=='collect': result='记录已保存到 '+str(client.collect(selected_output))
                else:
                    status=client.status() if action=='status' else client.control(action)
                    result=('记录已开启，拔开 USB 后仍会在手机保存。' if status['enabled'] else '记录已关闭。')
                    if status.get('performanceSampling'): result+=' 当前应用还开启了性能采样。'
            except Exception as e:
                result=str(e)
                if action=='status':connection.clear()
            def complete():
                message.set(result)
                for button in buttons: button.configure(state='normal')
            window.after(0,complete)
        threading.Thread(target=work,daemon=True).start()
    row=ttk.Frame(frame); row.grid(row=3,column=0,columnspan=3,pady=18)
    for text,action in [('连接 / 状态','status'),('开启记录','start'),('停止记录','stop'),('读取记录','collect')]:
        button=ttk.Button(row,text=text,command=lambda a=action:operation(a));button.pack(side='left',padx=4);buttons.append(button)
    ttk.Label(frame,textvariable=message,wraplength=570,justify='left').grid(row=4,column=0,columnspan=3,sticky='w')
    ttk.Label(frame,text='关闭此窗口不会停止手机记录；需要停止时点击“停止记录”。',wraplength=570).grid(row=5,column=0,columnspan=3,sticky='w',pady=12)
    frame.columnconfigure(1,weight=1); refresh(); window.mainloop()

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('action',nargs='?',choices=['status','start','stop','collect'])
    p.add_argument('--serial');p.add_argument('--package',default=DEFAULT_PACKAGE)
    p.add_argument('--output',default=str(Path.home()/'Documents'/'GalleryDiagnostics'))
    a=p.parse_args()
    if not a.action: gui()
    else:
        client=Listener(a.serial,a.package)
        if a.action=='collect': print(client.collect(a.output))
        else: print(json.dumps(client.status() if a.action=='status' else client.control(a.action),ensure_ascii=False))
