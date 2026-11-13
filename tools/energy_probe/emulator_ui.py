"""UI helpers for the existing isolated gallery fixture on emulator-5554.

UI inspection and screenshots are deliberately called outside energy windows.
"""
from pathlib import Path
import re
import subprocess
import time
from xml.etree import ElementTree as ET

OUT = Path.cwd()
PKG = 'com.pixel.gallery.codexclean'
SERIAL = 'emulator-5554'


def adb(*args):
    return subprocess.check_output(['adb', '-s', SERIAL, *args], stderr=subprocess.STDOUT)


def shell(command):
    return adb('shell', command).decode('utf-8', errors='replace')


def nodes():
    for _ in range(3):
        shell('uiautomator dump /sdcard/motion-power-audit.xml')
        xml = adb('exec-out', 'cat', '/sdcard/motion-power-audit.xml')
        if xml.lstrip().startswith(b'<?xml'):
            (OUT / 'current.xml').write_bytes(xml)
            return list(ET.fromstring(xml).iter('node'))
        time.sleep(.6)
    raise RuntimeError('UI dump unavailable: ' + xml.decode(errors='replace'))


def labels():
    return [(n.get('text'), n.get('content-desc'), n.get('bounds')) for n in nodes()
            if n.get('text') or n.get('content-desc')]


def point(label):
    matches = [n for n in nodes() if label in (n.get('text'), n.get('content-desc'))]
    if not matches:
        raise RuntimeError('Missing UI element ' + label)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', matches[0].get('bounds')))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(x, y):
    shell(f'input tap {x} {y}')


def click(label):
    tap(*point(label))
    time.sleep(.4)


def back():
    shell('input keyevent 4')
    time.sleep(.45)


def swipe(x, y, xx, yy, ms=400):
    shell(f'input swipe {x} {y} {xx} {yy} {ms}')


def shot(name):
    (OUT / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))


def cpu():
    pid = shell('pidof ' + PKG).strip().split()[0]
    stat = shell('run-as ' + PKG + ' cat /proc/' + pid + '/stat').rsplit(')', 1)[1].split()
    # Android CLK_TCK=100 was checked on this fixture; fields 14/15 are utime/stime.
    return {'pid': pid, 'cpu_ms': (int(stat[11]) + int(stat[12])) * 10}


def gfx(name):
    text = shell('dumpsys gfxinfo ' + PKG + ' framestats')
    (OUT / (name + '.gfx.txt')).write_text(text, encoding='utf-8')
    return int(re.search(r'Total frames rendered:\s*(\d+)', text).group(1)), text


def at_display():
    texts = [text for text, _, _ in labels()]
    if 'Display' in texts and 'Theme mode' in texts:
        return
    for label in ['More', 'Settings', 'Display']:
        click(label)


def home():
    for _ in range(7):
        current = labels()
        if any('At a glance' in (t, d) for t, d, _ in current):
            shell('am start -n ' + PKG + '/com.pixel.gallery.MainActivity')
            time.sleep(5)
            continue
        if any(t == 'Pixel Gallery' for t, _, _ in current):
            return
        if any(t == 'Cancel' for t, _, _ in current):
            click('Cancel')
        else:
            back()
    raise RuntimeError('Home not reached')
