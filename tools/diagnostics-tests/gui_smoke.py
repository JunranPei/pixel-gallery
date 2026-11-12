"""Exercise the real Tk connect button against an explicitly selected emulator."""
import argparse
import json
from pathlib import Path
import sys
import time
import tkinter as tk
from tkinter import ttk

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from viewer_listener import gui

p=argparse.ArgumentParser()
p.add_argument('--serial',required=True)
p.add_argument('--package',required=True)
a=p.parse_args()
assert a.serial.startswith('emulator-')
assert a.package.startswith('com.pixel.gallery.listener')
original_tk=tk.Tk
state={}
def widgets(parent):
    for child in parent.winfo_children():
        yield child
        yield from widgets(child)
def connect():
    root=state['root'];children=list(widgets(root))
    next(w for w in children if isinstance(w,ttk.Combobox)).set(a.serial)
    entry=next(w for w in children if isinstance(w,ttk.Entry) and not isinstance(w,ttk.Combobox) and w.grid_info().get('row')==1)
    entry.delete(0,'end');entry.insert(0,a.package)
    state['button']=next(w for w in children if isinstance(w,ttk.Button) and w.cget('text')=='连接 / 状态')
    state['button'].invoke()
    state['deadline']=time.monotonic()+20
    root.after(100,check)
def check():
    root=state['root']
    messages=[root.getvar(w.cget('textvariable')) for w in widgets(root) if isinstance(w,ttk.Label) and w.cget('textvariable')]
    if str(state['button'].cget('state'))=='normal':
        state['messages']=messages
        state['pass']=any('记录已关闭' in message for message in messages)
        root.destroy()
    elif time.monotonic()>state['deadline']:
        state['pass']=False;state['messages']=messages;root.destroy()
    else:root.after(100,check)
def create():
    root=original_tk();root.withdraw();state['root']=root
    root.after(300,connect)
    return root
tk.Tk=create
gui()
print(json.dumps({k:v for k,v in state.items() if k in ('pass','messages')},ensure_ascii=True))
assert state.get('pass'),state.get('messages')
