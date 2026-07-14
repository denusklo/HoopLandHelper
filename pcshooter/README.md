# pcshooter — PC-side auto-shooter

Replaces the on-phone Kotlin capture/timing path (which thermally throttled the phone and
corrupted its own timing). The phone only hardware-encodes its screen and receives raw touch
events; all analysis runs here. The Kotlin app remains as reference only.

```
adb screenrecord (H.264, hw encoder) ──► PyAV decode ──► bar-row scan (cursor + green zone)
                                                              │
persistent root shell ◄── evdev blobs (down/up/pass) ◄── release scheduler
                                                          (GridClock vsync timeline,
                                                           linear predict, adaptive L_eff)
```

## Setup (once per device/boot)

```bash
# WiFi adb from WSL2 (or use Windows adb.exe over USB — ADB constant in shoot.py)
adb connect <phone-ip>:5555
```

Touch blobs are generated and pushed automatically at every startup (`push_blobs()`),
for both landscape rotations; shoot.py picks the right set per shot via dumpsys
SurfaceOrientation, so rotating the phone between shots just works. Button screen
coords are the `SHOOT_POS` / `PASS_POS` constants in shoot.py.

## Run

```bash
.venv/bin/python shoot.py --calibrate --shots 5          # measure L_eff (sacrificial shots)
.venv/bin/python shoot.py --shots 20 --label <cfg> --adapt --save-frames
.venv/bin/python shoot.py --shots 20 --interactive --label pc-v17  # keep shooting; Ctrl+C to stop
.venv/bin/python test_shoot.py                           # logic self-checks (no device)
```

`--interactive` is an unbounded practice mode. It waits for Enter before every
shot, ignores `--shots`, and exits cleanly only when you press Ctrl+C. Without
`--interactive`, `--shots N` remains a finite batch run.

Game must be in a **free-play mode** — the practice tutorial gates buttons stage-by-stage
and breaks volume runs. Per-shot JSONL logs land in `logs/<label>.jsonl`;
`--save-frames` groups key frames (press/armed/release/rest) per shot under
`logs/<label>/shotNNN/` — taken from the existing stream, zero extra phone load.

Calibration values (bar region, shoot pos) were pulled from the app's SharedPreferences;
constants live at the top of `shoot.py`. Tuning history: `docs/harness/LESSONS.md`.

## Phone-button trigger (`--serve`)

Tap the app's overlay AUTO button instead of pressing Enter in the terminal:

```bash
.venv/bin/python shoot.py --serve --label tourney   # then tap AUTO on the phone
```

```
Kotlin overlay AUTO button ──Log.d "AUTO tapped"──► adb logcat -s HoopLandHelper
                                                        └─► serve() fires take_shot()
                                                            (no auto-pass, no retry)
```

The trigger travels over adb (logcat), not the network: HTTP + `adb reverse` was tried
first and rejected — Windows→WSL2 localhost forwarding is broken on the dev machine
(probed 2026-07-13: phone→Windows ok, Windows→WSL2 000). Button flashes yellow on tap;
shot results appear in the terminal and `logs/<label>.jsonl`. Double-taps during a shot
are drained, one shot at a time. The trigger is not latency-critical — only
capture→decide→release is, and that stays on adb.

**Not doable: any WAN hop (Cloudflare tunnel etc.) inside the timing loop** (frames up /
release command down). The model absorbs constant latency (l_eff) but not jitter: at
0.45 px/ms and ~±15-20px zone half-width the total jitter budget is ±35-45ms, and internet
tunnels jitter by tens of ms. LAN (1-5ms RTT) fits the budget; USB is best. Changing the
injection transport (USB ↔ WiFi adb) changes the latency constant — re-measure l_eff after
any switch (pc-v12 baseline: 84ms over current transport).
