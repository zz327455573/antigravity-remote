"""
Antigravity 遥控网关生命周期看门狗（纯 Python Win32 API 零子进程无黑框版）：
- 检测到 Antigravity.exe 在运行且 18900 端口未监听 -> 静默拉起 gateway.py
- 检测到 Antigravity.exe 已关闭且 gateway.py 在运行 -> 自动关闭网关进程
"""
import ctypes
import ctypes.wintypes as wintypes
import os
import socket
import subprocess
import sys

GATEWAY_DIR = os.path.dirname(os.path.abspath(__file__))
GATEWAY_PY = os.path.join(GATEWAY_DIR, "gateway.py")
PORT = 18900

TH32CS_SNAPPROCESS = 0x00000002


class PROCESSENTRY32W(ctypes.Structure):
    _fields_ = [
        ("dwSize", wintypes.DWORD),
        ("cntUsage", wintypes.DWORD),
        ("th32ProcessID", wintypes.DWORD),
        ("th32DefaultHeapID", ctypes.POINTER(ctypes.c_ulong)),
        ("th32ModuleID", wintypes.DWORD),
        ("cntThreads", wintypes.DWORD),
        ("th32ParentProcessID", wintypes.DWORD),
        ("pcPriClassBase", ctypes.c_long),
        ("dwFlags", wintypes.DWORD),
        ("szExeFile", wintypes.WCHAR * 260),
    ]


def is_process_running(target_exe: str) -> bool:
    kernel32 = ctypes.windll.kernel32
    snap = kernel32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
    if snap == -1:
        return False
    try:
        pe = PROCESSENTRY32W()
        pe.dwSize = ctypes.sizeof(PROCESSENTRY32W)
        if not kernel32.Process32FirstW(snap, ctypes.byref(pe)):
            return False
        target_low = target_exe.lower()
        while True:
            if pe.szExeFile.lower() == target_low:
                return True
            if not kernel32.Process32NextW(snap, ctypes.byref(pe)):
                break
    finally:
        kernel32.CloseHandle(snap)
    return False


def is_gateway_listening() -> bool:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(1.0)
    try:
        s.connect(("127.0.0.1", PORT))
        s.close()
        return True
    except Exception:
        return False


def main():
    agy_on = is_process_running("Antigravity.exe")
    gw_on = is_gateway_listening()

    if agy_on and not gw_on:
        pyw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
        exe = pyw if os.path.exists(pyw) else sys.executable
        si = subprocess.STARTUPINFO()
        si.dwFlags |= subprocess.STARTF_USESHOWWINDOW
        si.wShowWindow = 0
        subprocess.Popen(
            [exe, GATEWAY_PY],
            cwd=GATEWAY_DIR,
            startupinfo=si,
            creationflags=subprocess.CREATE_NO_WINDOW,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            stdin=subprocess.DEVNULL,
            close_fds=True,
        )
    elif not agy_on and gw_on:
        pid_file = os.path.join(GATEWAY_DIR, "gateway.pid")
        if os.path.exists(pid_file):
            try:
                pid = int(open(pid_file).read().strip())
                handle = ctypes.windll.kernel32.OpenProcess(1, False, pid)
                if handle:
                    ctypes.windll.kernel32.TerminateProcess(handle, 0)
                    ctypes.windll.kernel32.CloseHandle(handle)
            except Exception:
                pass


if __name__ == "__main__":
    main()
