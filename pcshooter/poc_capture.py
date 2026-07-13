#!/usr/bin/env python3
"""POC: measure capture fps and end-to-end latency (inject press -> meter visible in stream).

Runs N press/hold/release cycles against the live game (must be on a shooting-capable
screen, e.g. practice mode). Prints fps, per-cycle press->seen and release->gone latency.
"""
import subprocess, sys, threading, time

import av
import numpy as np

ADB = "/mnt/c/Users/sean1/AppData/Local/Android/Sdk/platform-tools/adb.exe"  # USB path
BAR = (945, 90, 1408, 153)  # left, top, right, bottom (landscape screen px)
CYCLES = 3
HOLD_S = 1.0


def meter_visible(frame_rgb):
    """Meter = dark strip replaces court/wood in bar region. Court mean ~[86,66,51] RGB, meter bg near-black."""
    l, t, r, b = BAR
    roi = frame_rgb[t:b, l:r]
    dark = (roi.sum(axis=2) < 180).mean()
    return dark > 0.35


def main():
    shell = subprocess.Popen([ADB, "shell", "su"], stdin=subprocess.PIPE,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def inject(blob):
        t = time.monotonic()
        shell.stdin.write(f"cat /data/local/tmp/{blob} > /dev/input/event3\n".encode())
        shell.stdin.flush()
        return t

    rec = subprocess.Popen(
        [ADB, "exec-out", "screenrecord", "--output-format=h264", "--bit-rate", "8000000", "-"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)

    frames = []  # (t_arrival, meter_visible)
    stop = threading.Event()

    def decode():
        container = av.open(rec.stdout, format="h264", mode="r")
        for frame in container.decode(video=0):
            t = time.monotonic()
            rgb = frame.to_ndarray(format="rgb24")
            frames.append((t, meter_visible(rgb), rgb.shape))
            if stop.is_set():
                break

    th = threading.Thread(target=decode, daemon=True)
    th.start()

    # wait for stream to produce frames
    t_wait = time.monotonic()
    while len(frames) < 10:
        if time.monotonic() - t_wait > 15:
            print("FATAL: no frames after 15s"); sys.exit(1)
        time.sleep(0.1)
    print(f"stream up, frame shape {frames[-1][2]}")

    # fps over 3s
    n0, t0 = len(frames), time.monotonic()
    time.sleep(3)
    n1, t1 = len(frames), time.monotonic()
    fps = (n1 - n0) / (t1 - t0)
    print(f"fps: {fps:.1f}")

    for c in range(CYCLES):
        base = len(frames)
        t_press = inject("down.bin")
        lat_press = None
        while time.monotonic() - t_press < 3:
            for tf, vis, _ in frames[base:]:
                if vis and tf > t_press:
                    lat_press = (tf - t_press) * 1000
                    break
            if lat_press: break
            time.sleep(0.005)
        time.sleep(HOLD_S)
        base = len(frames)
        t_rel = inject("up.bin")
        lat_rel = None
        while time.monotonic() - t_rel < 3:
            for tf, vis, _ in frames[base:]:
                if not vis and tf > t_rel:
                    lat_rel = (tf - t_rel) * 1000
                    break
            if lat_rel: break
            time.sleep(0.005)
        print(f"cycle {c}: press->meter {lat_press and f'{lat_press:.0f}ms'}, "
              f"release->gone {lat_rel and f'{lat_rel:.0f}ms'}")
        time.sleep(1.5)

    stop.set()
    rec.kill()
    shell.stdin.write(b"exit\n"); shell.stdin.flush()
    # inter-frame jitter from last 100 frames
    ts = [t for t, _, _ in frames[-100:]]
    dts = np.diff(ts) * 1000
    print(f"inter-frame dt ms: median {np.median(dts):.1f}, p95 {np.percentile(dts, 95):.1f}, max {dts.max():.1f}")


if __name__ == "__main__":
    main()
