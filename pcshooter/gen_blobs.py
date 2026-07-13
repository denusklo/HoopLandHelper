#!/usr/bin/env python3
"""Generate raw evdev input_event blobs (down.bin / up.bin) for a tap-and-hold
at a given landscape screen coordinate, for goodix_ts on raphael (portrait raw axes,
1080x2340, type-B multitouch + BTN_TOUCH).

Usage: gen_blobs.py <screen_x> <screen_y> <outdir> [--rot270]
Default mapping is for display ROTATION_90: raw_x = 1079 - sy, raw_y = sx.
"""
import struct, sys, pathlib

EV_SYN, EV_KEY, EV_ABS = 0, 1, 3
SYN_REPORT = 0
BTN_TOUCH, BTN_TOOL_FINGER = 0x14A, 0x145
ABS_MT_SLOT, ABS_MT_POSITION_X, ABS_MT_POSITION_Y, ABS_MT_TRACKING_ID = 0x2F, 0x35, 0x36, 0x39
ABS_X, ABS_Y = 0x00, 0x01

RAW_XMAX, RAW_YMAX = 1079, 2339


def ev(type_, code, value):
    # struct input_event on arm64: sec(8) usec(8) type(u16) code(u16) value(s32)
    return struct.pack("<qqHHI", 0, 0, type_, code, value & 0xFFFFFFFF)


def blobs(sx, sy, rot270=False):
    if rot270:
        rx, ry = sy, RAW_YMAX - sx
    else:  # ROTATION_90
        rx, ry = RAW_XMAX - sy, sx
    assert 0 <= rx <= RAW_XMAX and 0 <= ry <= RAW_YMAX, (rx, ry)
    down = b"".join([
        ev(EV_ABS, ABS_MT_SLOT, 0),
        ev(EV_ABS, ABS_MT_TRACKING_ID, 4242),
        ev(EV_KEY, BTN_TOUCH, 1),
        ev(EV_KEY, BTN_TOOL_FINGER, 1),
        ev(EV_ABS, ABS_MT_POSITION_X, rx),
        ev(EV_ABS, ABS_MT_POSITION_Y, ry),
        ev(EV_ABS, ABS_X, rx),
        ev(EV_ABS, ABS_Y, ry),
        ev(EV_SYN, SYN_REPORT, 0),
    ])
    up = b"".join([
        ev(EV_ABS, ABS_MT_SLOT, 0),
        ev(EV_ABS, ABS_MT_TRACKING_ID, -1),
        ev(EV_KEY, BTN_TOUCH, 0),
        ev(EV_KEY, BTN_TOOL_FINGER, 0),
        ev(EV_SYN, SYN_REPORT, 0),
    ])
    return down, up


if __name__ == "__main__":
    sx, sy, outdir = int(sys.argv[1]), int(sys.argv[2]), pathlib.Path(sys.argv[3])
    down, up = blobs(sx, sy, rot270="--rot270" in sys.argv)
    (outdir / "down.bin").write_bytes(down)
    (outdir / "up.bin").write_bytes(up)
    print(f"wrote {outdir}/down.bin {len(down)}B, up.bin {len(up)}B")
