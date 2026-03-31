#!/usr/bin/env python3
"""HoopLand ADB Relay — runs on Windows to inject touch via ADB.

Setup:
1. Connect ADB over WiFi:  adb connect <phone_ip>:5555
2. Set up reverse port:    adb reverse tcp:9999 tcp:9999
3. Run this script:         python relay.py
"""

import socket
import subprocess
import threading
import time

PORT = 9999
hold_proc = None
lock = threading.Lock()
DEVICE = None  # set via --device flag


def adb_shell(cmd):
    """Run an adb shell command targeting the specific device."""
    if DEVICE:
        full = f"adb -s {DEVICE} shell {cmd}"
    else:
        full = f"adb shell {cmd}"
    return full


def handle_hold(x, y, duration):
    global hold_proc
    with lock:
        if hold_proc:
            hold_proc.terminate()
            try:
                hold_proc.wait(timeout=1)
            except:
                pass

    cmd = adb_shell(f"input swipe {x} {y} {x} {y} {duration}")
    print(f"  -> {cmd}")
    proc = subprocess.Popen(cmd, shell=True)

    with lock:
        hold_proc = proc

    proc.wait()

    with lock:
        if hold_proc == proc:
            hold_proc = None
    print(f"  -> Hold ended")


def handle_release(x, y):
    global hold_proc
    print(f"  -> Releasing at ({x}, {y})")
    with lock:
        if hold_proc:
            hold_proc.terminate()
            try:
                hold_proc.wait(timeout=1)
            except:
                pass
            hold_proc = None

    time.sleep(0.05)
    cmd = adb_shell(f"input tap {x} {y}")
    print(f"  -> {cmd}")
    subprocess.run(cmd, shell=True)


def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", PORT))
    server.listen(1)
    print(f"HoopLand ADB Relay listening on port {PORT}")
    print("Make sure you ran: adb reverse tcp:9999 tcp:9999")
    print("Waiting for phone connection...\n")

    while True:
        conn, addr = server.accept()
        print(f"Phone connected from {addr}")
        buf = ""
        try:
            while True:
                data = conn.recv(4096)
                if not data:
                    break
                buf += data.decode()
                while "\n" in buf:
                    line, buf = buf.split("\n", 1)
                    line = line.strip()
                    if not line:
                        continue
                    print(f"<< {line}")
                    parts = line.split()
                    if parts[0] == "hold" and len(parts) >= 4:
                        threading.Thread(
                            target=handle_hold,
                            args=(parts[1], parts[2], parts[3]),
                            daemon=True,
                        ).start()
                    elif parts[0] == "release" and len(parts) >= 3:
                        handle_release(parts[1], parts[2])
                    elif parts[0] == "ping":
                        conn.sendall(b"pong\n")
        except ConnectionResetError:
            pass
        print("Phone disconnected. Waiting...\n")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="HoopLand ADB Relay")
    parser.add_argument("--device", "-d", default="192.168.0.9:5555",
                        help="ADB device serial (default: 192.168.0.9:5555)")
    args = parser.parse_args()
    DEVICE = args.device
    print(f"Target device: {DEVICE}")
    main()
