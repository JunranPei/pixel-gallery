"""Read Windows' existing EMI counters. No driver installation or settings writes.

API layouts: Microsoft emi.h and cfgmgr32.h.
https://learn.microsoft.com/windows-hardware/drivers/powermeter/energy-meter-interface
Metadata offsets were cross-checked against CodeCarbon's Windows EMI reader:
https://github.com/mlco2/codecarbon/blob/master/codecarbon/core/windows_emi.py
"""
from __future__ import annotations

import ctypes as c
from ctypes import wintypes as w
import struct
import time
import uuid

PWH_TO_J = 3.6e-9


def decode_metadata(version: int, data: bytes) -> list[dict]:
    result = []
    if version == 1:
        if len(data) < 72:
            raise ValueError('Truncated EMI v1 metadata')
        unit, = struct.unpack_from('<I', data)
        size, = struct.unpack_from('<H', data, 70)
        entries = [(unit, 72, size)]
    elif version == 2:
        if len(data) < 68:
            raise ValueError('Truncated EMI v2 metadata')
        count, = struct.unpack_from('<H', data, 66)
        entries = []
        offset = 68
        for _ in range(count):
            unit, size = struct.unpack_from('<IH', data, offset)
            entries.append((unit, offset + 6, size))
            offset += 6 + size
    else:
        raise ValueError(f'Unsupported EMI version {version}')
    for unit, offset, size in entries:
        if unit != 0:
            raise ValueError(f'Unsupported EMI measurement unit {unit}')
        if size % 2 or offset + size > len(data):
            raise ValueError('Truncated or unaligned EMI channel name')
        name = data[offset:offset + size].decode('utf-16-le').rstrip('\0')
        result.append({'name': name, 'unit': 'picowatt_hour'})
    return result


class WindowsMeter:
    def __init__(self):
        self.kernel = c.WinDLL('kernel32', use_last_error=True)
        cfg = c.WinDLL('cfgmgr32', use_last_error=True)
        self.kernel.CreateFileW.argtypes = [w.LPCWSTR, w.DWORD, w.DWORD, w.LPVOID, w.DWORD, w.DWORD, w.HANDLE]
        self.kernel.CreateFileW.restype = w.HANDLE
        self.kernel.DeviceIoControl.argtypes = [w.HANDLE, w.DWORD, w.LPVOID, w.DWORD, w.LPVOID, w.DWORD, c.POINTER(w.DWORD), w.LPVOID]
        self.kernel.DeviceIoControl.restype = w.BOOL
        self.kernel.CloseHandle.argtypes = [w.HANDLE]
        cfg.CM_Get_Device_Interface_List_SizeW.argtypes = [c.POINTER(w.ULONG), c.c_void_p, w.LPCWSTR, w.ULONG]
        cfg.CM_Get_Device_Interface_ListW.argtypes = [c.c_void_p, w.LPCWSTR, w.LPWSTR, w.ULONG, w.ULONG]
        guid = c.create_string_buffer(uuid.UUID('45bd8344-7ed6-49cf-a440-c276c933b053').bytes_le)
        size = w.ULONG()
        if cfg.CM_Get_Device_Interface_List_SizeW(c.byref(size), guid, None, 0) or size.value < 2:
            raise RuntimeError('No Windows EMI device available')
        buf = c.create_unicode_buffer(size.value)
        if cfg.CM_Get_Device_Interface_ListW(guid, None, buf, size.value, 0):
            raise RuntimeError('Cannot enumerate Windows EMI devices')
        self.devices = []
        self.errors = []
        for path in ''.join(buf[:size.value]).split('\0'):
            if not path:
                continue
            handle = self.kernel.CreateFileW(path, 0x80000000, 3, None, 3, 0, None)
            if handle in (None, w.HANDLE(-1).value):
                self.errors.append({'path': path, 'error': c.get_last_error()})
                continue
            try:
                version, = struct.unpack_from('<H', self._io(handle, 0x224000, 2))
                n, = struct.unpack_from('<I', self._io(handle, 0x224004, 4))
                if not 72 <= n <= 1048576:
                    raise ValueError(f'Invalid metadata size {n}')
                channels = decode_metadata(version, self._io(handle, 0x224008, n))
                self.devices.append({'handle': handle, 'path': path, 'version': version, 'channels': channels})
            except Exception as exc:
                self.kernel.CloseHandle(handle)
                self.errors.append({'path': path, 'error': repr(exc)})
        names = [x['name'] for d in self.devices for x in d['channels']]
        self.package_names = [n for n in names if n.lower().endswith('_pkg')]
        if len(self.package_names) != 1:
            self.close()
            raise RuntimeError(f'Expected one unambiguous package counter, found {self.package_names}; available={names}')

    def _io(self, handle, code, size):
        buf = c.create_string_buffer(size)
        used = w.DWORD()
        if not self.kernel.DeviceIoControl(handle, code, None, 0, buf, size, c.byref(used), None):
            raise c.WinError(c.get_last_error())
        return buf.raw[:used.value]

    def describe(self):
        return {'backend': 'Windows EMI / existing OS energy devices', 'scope': 'host_package',
                'package_channels': self.package_names,
                'channels': [x for d in self.devices for x in d['channels']], 'errors': self.errors,
                'package_and_subdomains_are_not_summed': True}

    def snapshot(self):
        channels = {}
        for d in self.devices:
            raw = self._io(d['handle'], 0x22400c, 16 * len(d['channels']))
            if len(raw) != 16 * len(d['channels']):
                raise RuntimeError('Truncated EMI measurement')
            for index, ch in enumerate(d['channels']):
                energy, stamp = struct.unpack_from('<QQ', raw, index * 16)
                channels[ch['name']] = {'energy_pwh': energy, 'time_100ns': stamp}
        return {'monotonic_ns': time.perf_counter_ns(), 'channels': channels}

    def close(self):
        for d in self.devices:
            self.kernel.CloseHandle(d['handle'])
        self.devices = []

    def __enter__(self): return self
    def __exit__(self, *args): self.close()
