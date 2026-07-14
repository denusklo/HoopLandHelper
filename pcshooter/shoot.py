#!/usr/bin/env python3
"""PC-side auto-shooter for Hoop Land (replaces the on-phone Kotlin path).

Pipeline: adb screenrecord H.264 stream -> PyAV decode -> bar-row analysis
(cursor + green zone, ported from GreenZoneDetector.kt) -> linear prediction
-> scheduled release via raw evdev blobs through a persistent root shell.

Modes:
  --calibrate        take N fixed-hold shots, measure effective latency L_eff
                     (cursor px traveled between send-time and actual stop, / speed)
  --shots N          take N real shots, log per-shot JSON lines
  --label X          config label written to every log line

Timestamps: motion math uses encoder PTS (device clock, vsync-accurate);
a min-offset clock bridge maps PTS -> PC monotonic for scheduling.
"""
import argparse, itertools, json, pathlib, subprocess, sys, threading, time

import av
import cv2
import numpy as np

ADB = "/mnt/c/Users/sean1/AppData/Local/Android/Sdk/platform-tools/adb.exe"
TOUCH_DEV = "/dev/input/event3"

# Live calibration pulled from the app's SharedPreferences 2026-07-13.
BAR = (945, 90, 1408, 153)            # left, top, right, bottom, screen px
SHOOT_POS = (2187, 774)
PASS_POS = (2030, 933)

# --- tuning constants (one variable per iteration; ledger in docs/harness/LESSONS.md) ---
L_EFF_MS = 84.0         # effective latency: send -> cursor actually stops. pc-v12 (n=13):
                        # fixed 84 beats the adapt controller (|err| 5.5 vs 7.0px median).
TARGET_FRACTION = 0.5   # aim point inside green zone (0=left edge, 1=right edge)
ARM_FRACTION = 0.35     # cursor must first be seen left of this fraction of bar width
MIN_SPEED_SAMPLES = 6   # 4 allowed a bad early fit (measured 0.68 vs true 0.44 px/ms)
FIT_LAST_N = 8          # speed fit window. 12 tested worse (pc-v11: |err| 7->14.5px):
                        # the sweep isn't constant-speed, long windows fit average
                        # speed, the release needs current speed. Don't raise this.
FIT_MIN_SPAN_MS = 70.0  # six samples must cover several display frames; a burst of
                        # duplicate/junk frames is not enough to predict a release
NO_METER_ABORT_S = 0.8  # press -> no cursor/zone seen: game wasn't ready, abort + retry
                        # (was 1.5: meter shows <0.3s when it will; long hold just charges a dunk)
BALL_FLIGHT_S = 1.8     # release -> shooter auto-catches; Pass is dead while ball is airborne
HANDLER_SAFE_X = 1000   # handler-label x >= this = clear of the paint (measured: dunks
                        # pressed at 717-912, clean meter shots at 1011-1186)
SHOT_TIMEOUT_S = 3.0    # absolute safety net
MAX_HOLD_S = 1.6        # never hold past this: over-holding keeps the ball in hand
                        # (and fouls in real games) — better a bad shot than no shot
CURSOR_MIN_BRIGHTNESS = 600   # of 765, from GreenZoneDetector.kt
GREEN_MIN_RUN, GREEN_MAX_RUN, GREEN_MAX_GAP = 8, 60, 4
MAX_STEP_PX = 35        # cursor moves ~10-20px/frame; bigger jump = detection glitch
SPEED_BOUNDS = (0.15, 0.65)  # px/ms plausibility gate (observed true speed ~0.42-0.50)
FIT_MAX_RESIDUAL_PX = 8.0    # reject a fit containing a brightness-argmax jump
ZONE_STABLE_N = 3       # zone must be sighted this many times within +/-6px center
AUTOCAL_WINDOW = 7      # rolling median of the last N measured_l_eff -> next l_eff.
AUTOCAL_MIN = 3         # need this many measurements before overriding the seed.
RELEASE_PHASE_MS = 6.0  # pc-v15: send this many ms before a display-frame boundary, so
                        # the release (transport ~2ms) lands ~4ms ahead of the frame the
                        # game samples it on — constant phase instead of 0..16.7ms random
STREAM_WARMUP_FRAMES = 24  # a fresh screenrecord has a short burst of junk frames;
                           # wait for a real current-stream window before shooting
METER_MIN_DARK_FRACTION = 0.20
METER_MIN_ACCENT_PIXELS = 8

BLOB_DIR = "/data/local/tmp"
DEBUG = False


# ---------- detection (port of GreenZoneDetector.kt single-row scan) ----------

def analyze_row(row):
    """row: (W,3) uint8 RGB of the bar's middle line, bar-relative x.
    Returns (cursor_x or None, (green_left, green_right) or None)."""
    r = row[:, 0].astype(np.int32)
    g = row[:, 1].astype(np.int32)
    b = row[:, 2].astype(np.int32)
    bright = r + g + b

    # measured in-game zone color is dark green (46,106,66) — the Kotlin ratio test
    # (g/(r+b) > 1.3) misses it; green-dominant is sufficient against this bar's
    # palette (black bg, orange segments, white cursor)
    greenish = (g > r) & (g > b) & (g >= 60)
    w = len(row)
    greenish[: int(0.05 * w)] = False
    greenish[int(0.95 * w):] = False
    zone = best_run(greenish)

    # When the meter is absent, court lines and player graphics can be brighter
    # than anything in this row.  Treating the global argmax as a cursor in that
    # state arms a shot on a non-meter frame (the saved pc-v10 shot013 evidence
    # showed exactly that).  The active meter has a black track plus orange/green
    # accents, while the inactive court row does not.
    dark = bright < 180
    orangeish = (r >= 100) & (r > g + 25) & (r > b + 45) & (g >= 40)
    meter_present = (float(dark.mean()) >= METER_MIN_DARK_FRACTION
                     and (zone is not None
                          or int(orangeish.sum()) >= METER_MIN_ACCENT_PIXELS))
    if not meter_present:
        return None, None

    cx = int(np.argmax(bright))
    cursor = cx if bright[cx] > CURSOR_MIN_BRIGHTNESS else None
    return cursor, zone


def best_run(mask):
    """Widest run of True, merging gaps <= GREEN_MAX_GAP, width GREEN_MIN_RUN..GREEN_MAX_RUN."""
    idx = np.flatnonzero(mask)
    if len(idx) == 0:
        return None
    runs, start, prev = [], idx[0], idx[0]
    for i in idx[1:]:
        if i - prev > GREEN_MAX_GAP + 1:
            runs.append((start, prev))
            start = i
        prev = i
    runs.append((start, prev))
    runs = [(a, b) for a, b in runs if GREEN_MIN_RUN <= b - a + 1 <= GREEN_MAX_RUN]
    if not runs:
        return None
    return max(runs, key=lambda ab: ab[1] - ab[0])


# ---------- capture ----------

class GridClock:
    """Raw H.264 from screenrecord has no PTS. Reconstruct device frame times by
    snapping inter-arrival deltas to the 60Hz vsync grid, anchored to the
    minimum-latency arrival over a sliding window (arrival jitter is one-sided:
    frames arrive late, never early)."""
    V = 1000.0 / 60.0

    def __init__(self):
        self.n = None
        self.anchor = None
        self.window = []   # (n, arrival - n*V)

    def add(self, arrival_ms):
        if self.n is None:
            self.n, self.anchor = 0, arrival_ms
        else:
            # absolute slot vs anchor (self-corrects after bursts); frames never share a slot
            self.n = max(self.n + 1, round((arrival_ms - self.anchor) / self.V))
        grid = self.n * self.V
        self.window.append((self.n, arrival_ms - grid))
        self.window = [(n, l) for n, l in self.window if self.n - n <= 180]
        self.anchor = min(l for _, l in self.window)
        return grid + self.anchor   # est true display time, wall-clock ms


class Capture:
    """screenrecord -> av decode thread. Keeps last frames' (est_ms, arrival, cursor, zone)."""

    def __init__(self):
        self.samples = []            # (est_wall_ms, arrival_monotonic, cursor_x|None, zone|None)
        self.lock = threading.Lock()
        self.alive = False
        self._proc = None
        self._decode_thread = None
        self._generation = 0         # prevents a killed decoder draining into a new stream
        self._stream_frames = 0      # frames decoded by the current generation only
        self.last_rgb = None         # latest decoded frame (reference swap, no copy)
        self.last_arrival = 0.0      # monotonic time of the newest decoded frame
        self.t0 = 0.0                # when the current screenrecord process started
        self._ensure_lock = threading.Lock()

    def start(self):
        # kill any orphaned device-side screenrecord first (an interrupted session
        # can leave one holding the display -> new streams yield no frames)
        # Invalidate the old generation before joining it.  Without this, PyAV can
        # drain buffered frames from the killed process after the new process has
        # started; those frames were able to pass the old ten-frame warmup gate.
        with self.lock:
            self._generation += 1
            generation = self._generation
            old_proc = self._proc
            old_thread = self._decode_thread
            self._proc = None
            self._decode_thread = None
            self.alive = False

        if old_proc:
            try:
                old_proc.kill()
            except ProcessLookupError:
                pass
        if old_thread and old_thread is not threading.current_thread():
            old_thread.join(timeout=2.0)

        subprocess.run([ADB, "shell", "pkill", "-f", "screenrecord"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        proc = subprocess.Popen(
            [ADB, "exec-out", "screenrecord", "--output-format=h264",
             "--bit-rate", "8000000", "-"],
            # stdin MUST be detached: adb exec-out forwards inherited stdin to the
            # device and steals keystrokes from input() (--interactive double-Enter)
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)

        with self.lock:
            # Keep these values tied to the new generation.  In particular,
            # last_arrival must not make ensure() believe a just-started stream is
            # healthy while it is still waiting for its first frames.
            self.clock = GridClock()
            self.t0 = time.monotonic()
            self._proc = proc
            self._stream_frames = 0
            self.last_rgb = None
            self.last_arrival = 0.0
            self.alive = True

        thread = threading.Thread(target=self._decode,
                                  args=(proc, generation), daemon=True)
        with self.lock:
            self._decode_thread = thread
        thread.start()

    def ensure(self, max_age_s=150.0):
        """screenrecord hard-stops at 180s (pc-v5 shots 14-19: frozen frames).
        Restart the stream when frames are stale or it is older than max_age_s,
        then block until the new stream has STABILIZED (a current-generation
        warmup window — the first post-restart frames gave garbage shots:
        n_track=0, fake cursors).
        Thread-safe: serve mode calls this from watchdog + handler threads."""
        with self._ensure_lock:
            now = time.monotonic()
            with self.lock:
                last_arrival = self.last_arrival
                t0 = self.t0
                alive = self.alive
                stream_frames = self._stream_frames
            if (alive and last_arrival > 0 and now - last_arrival < 2.0
                    and now - t0 < max_age_s
                    and stream_frames >= STREAM_WARMUP_FRAMES):
                return
            print(json.dumps({"event": "stream_restart",
                              "stale_s": round(now - last_arrival, 1),
                              "age_s": round(now - t0, 1)}), flush=True)
            self.start()
            with self.lock:
                generation = self._generation
            t_wait = time.monotonic()
            while True:
                with self.lock:
                    ready = (generation == self._generation
                             and self.alive
                             and self._stream_frames >= STREAM_WARMUP_FRAMES
                             and self.last_arrival > 0)
                if ready:
                    return
                if time.monotonic() - t_wait > 15:
                    raise RuntimeError("stream restart failed: no frames")
                time.sleep(0.05)

    def _decode(self, proc, generation):
        l, t, r, b = BAR
        mid = (t + b) // 2
        clock = GridClock()        # one independent vsync anchor per generation
        try:
            container = av.open(proc.stdout, format="h264", mode="r")
            for frame in container.decode(video=0):
                arrival = time.monotonic()
                rgb = frame.to_ndarray(format="rgb24")
                if rgb.shape[0] != 1080:   # rotation changed; bail
                    break
                cursor, zone = analyze_row(rgb[mid, l:r])
                with self.lock:
                    if generation != self._generation:
                        return
                    self.clock = clock   # publish: the vsync snap reads .anchor;
                                         # start()'s placeholder never gets frames
                    est_ms = clock.add(arrival * 1000)
                    self.samples.append((est_ms, arrival, cursor, zone))
                    # NEVER trim this list: take_shot tracks position by index, and
                    # a front-trim pins len() so consumers read [] forever (the
                    # every-run "no_meter after ~30s" bug). Tuples are tiny; hours
                    # of run time fit in a few MB.
                    self.last_rgb = rgb
                    self.last_arrival = arrival
                    self._stream_frames += 1
        finally:
            with self.lock:
                if generation == self._generation:
                    self.alive = False

    def pts_to_wall(self, est_ms):
        return est_ms / 1000   # GridClock output is already wall-clock

    def stop(self):
        with self.lock:
            self._generation += 1
            proc = self._proc
            thread = self._decode_thread
            self._proc = None
            self._decode_thread = None
            self.alive = False
        if proc:
            try:
                proc.kill()
            except ProcessLookupError:
                pass
        if thread and thread is not threading.current_thread():
            thread.join(timeout=2.0)


# ---------- injection ----------

def push_blobs():
    """Generate + push all touch blobs (both landscape rotations) at startup.
    Idempotent, ~0.2s — removes the manual gen_blobs.py setup step entirely."""
    import tempfile
    from gen_blobs import blobs
    d = pathlib.Path(tempfile.mkdtemp())
    files = {}
    files["down.bin"], files["up.bin"] = blobs(*SHOOT_POS)
    files["pass_down.bin"], files["pass_up.bin"] = blobs(*PASS_POS)
    files["down_r3.bin"], _ = blobs(*SHOOT_POS, rot270=True)
    files["pass_down_r3.bin"], _ = blobs(*PASS_POS, rot270=True)
    for name, data in files.items():
        (d / name).write_bytes(data)
    subprocess.run([ADB, "push", *(str(d / n) for n in files), BLOB_DIR + "/"],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def device_rotation():
    """1 = ROTATION_90 (anticlockwise landscape, the calibrated default),
    3 = ROTATION_270 (clockwise): raw digitizer coords flip 180 between them."""
    try:
        out = subprocess.run([ADB, "shell", "dumpsys input | grep -m1 SurfaceOrientation"],
                             capture_output=True, text=True, timeout=5).stdout
        return 3 if out.strip().endswith("3") else 1
    except Exception:
        return 1


class Injector:
    # transport note: measured 2026-07-14, shell RTT jitter is ~2ms (p95 <5) on both
    # adb.exe and native-adb/TCP — the per-shot latency variance is GAME-side frame
    # processing, not transport. Don't chase transports again (pc-v14 rejected).
    def __init__(self):
        self.rot = 1                 # updated per shot from device_rotation()
        self.shell = subprocess.Popen([ADB, "shell", "su"], stdin=subprocess.PIPE,
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def _sfx(self):
        return "_r3" if self.rot == 3 else ""   # only down blobs carry coordinates

    def _cat(self, blob):
        self.shell.stdin.write(f"cat {BLOB_DIR}/{blob} > {TOUCH_DEV}\n".encode())
        self.shell.stdin.flush()
        return time.monotonic()

    def press(self):
        return self._cat(f"down{self._sfx()}.bin")

    def release(self):
        return self._cat("up.bin")

    def tap_pass(self):
        """Quick tap on Pass — recovers a shootable state when Shoot yields no meter."""
        self.shell.stdin.write(
            f"cat {BLOB_DIR}/pass_down{self._sfx()}.bin > {TOUCH_DEV}; sleep 0.12; "
            f"cat {BLOB_DIR}/pass_up.bin > {TOUCH_DEV}\n".encode())
        self.shell.stdin.flush()

    def close(self):
        try:
            self.shell.stdin.write(b"exit\n")
            self.shell.stdin.flush()
        except Exception:
            pass


# ---------- shot logic ----------

def stable_zone(zones):
    """Median zone of the last sightings, only if >= ZONE_STABLE_N agree on center +/-6px."""
    recent = zones[-8:]
    if len(recent) < ZONE_STABLE_N:
        return None
    centers = np.array([(a + b) / 2 for a, b in recent])
    med = np.median(centers)
    agree = [z for z, c in zip(recent, centers) if abs(c - med) <= 6]
    if len(agree) < ZONE_STABLE_N:
        return None
    return float(np.median([z[0] for z in agree])), float(np.median([z[1] for z in agree]))


def fit_speed(samples):
    """px/ms from recent (pts_ms, cursor_x) via least squares. None if not enough."""
    if len(samples) < MIN_SPEED_SAMPLES:
        return None
    t = np.array([s[0] for s in samples])
    x = np.array([s[1] for s in samples])
    span = float(t[-1] - t[0])
    if span < FIT_MIN_SPAN_MS or np.any(np.diff(t) <= 0):
        return None

    # A single bad brightness argmax can still pass the monotonic/step gates and
    # badly tilt an ordinary least-squares fit.  The median of all pairwise
    # slopes is robust to one such point; use it to identify inliers, then fit
    # the current window normally so we retain the local sweep slope rather than
    # replacing it with a heavily smoothed average.
    slopes = []
    for i in range(len(t) - 1):
        dt = t[i + 1:] - t[i]
        slopes.extend(((x[i + 1:] - x[i]) / dt).tolist())
    robust_speed = float(np.median(slopes))
    if not SPEED_BOUNDS[0] <= robust_speed <= SPEED_BOUNDS[1]:
        return None

    intercept = float(np.median(x - robust_speed * (t - t[0])))
    residual = np.abs(x - (intercept + robust_speed * (t - t[0])))
    inliers = residual <= FIT_MAX_RESIDUAL_PX
    if int(inliers.sum()) < max(MIN_SPEED_SAMPLES - 1, 3):
        return None

    ti = t[inliers]
    xi = x[inliers]
    A = np.vstack([ti - ti[0], np.ones_like(ti)]).T
    speed, _ = np.linalg.lstsq(A, xi, rcond=None)[0]
    return float(speed) if SPEED_BOUNDS[0] <= speed <= SPEED_BOUNDS[1] else None


def take_shot(cap, inj, l_eff_ms, fixed_hold_s=None, snaps=None, log=print):
    """One shot. Returns dict with outcome. fixed_hold_s -> calibration mode (blind release).
    snaps: optional dict to receive key frames (already-decoded stream frames, no extra
    device capture) at events: press / armed / release / rest."""

    def snap(event):
        if snaps is not None and cap.last_rgb is not None and event not in snaps:
            snaps[event] = cap.last_rgb   # decoded array ref; written to disk by caller

    if snaps is not None:
        snaps.clear()   # retries reuse the dict; saved frames must match the final attempt
    bar_w = BAR[2] - BAR[0]
    with cap.lock:
        start_idx = len(cap.samples)
    t_press = inj.press()
    snap("press")

    armed = False
    track = []            # (pts_ms, cursor_x) after arming
    zones = []
    t_send = None
    x_at_send = speed = None
    align_off_ms = None   # how far pc-v15 vsync alignment moved the send
    rejects = 0

    seen_meter = False
    while time.monotonic() - t_press < SHOT_TIMEOUT_S:
        if not seen_meter and time.monotonic() - t_press > NO_METER_ABORT_S:
            inj.release()
            return {"ok": False, "reason": "no_meter"}
        with cap.lock:
            new = cap.samples[start_idx:]
            start_idx = len(cap.samples)
        for pts_ms, arrival, cursor, zone in new:
            if cursor is not None or zone:
                seen_meter = True
            if zone:
                zones.append(zone)
            if cursor is None:
                continue
            if not armed:
                if cursor < ARM_FRACTION * bar_w:
                    armed = True
                    snap("armed")
                else:
                    continue
            if track and pts_ms <= track[-1][0]:
                continue
            # reject non-physical samples: cursor only moves right, in bounded steps
            if track and not (0 <= cursor - track[-1][1] <= MAX_STEP_PX * max(1, round((pts_ms - track[-1][0]) / 16.7))):
                rejects += 1
                if rejects >= 3:
                    track.clear()   # detection lost the real cursor; start over
                    rejects = 0
                continue
            rejects = 0
            track.append((pts_ms, cursor))

        if fixed_hold_s is not None:
            if time.monotonic() - t_press >= fixed_hold_s:
                speed = fit_speed(track[-FIT_LAST_N:])
                x_at_send, t_send = _extrapolate_now(track, speed, cap)
                inj.release()
                break
            time.sleep(0.002)
            continue

        # over-hold guard: release even without a good prediction
        if fixed_hold_s is None and time.monotonic() - t_press >= MAX_HOLD_S:
            speed = fit_speed(track[-FIT_LAST_N:])
            x_at_send, t_send = _extrapolate_now(track, speed, cap)
            inj.release()
            break

        # predictive release: needs a stable zone (>=N sightings agreeing on center)
        stable = stable_zone(zones)
        if armed and stable and len(track) >= MIN_SPEED_SAMPLES:
            speed = fit_speed(track[-FIT_LAST_N:])
            if speed is None and track[-1][1] >= stable[0]:
                inj.release()   # fallback (Path A): cursor already at zone, fit unusable
                break
            if speed:
                gl, gr = stable
                target = gl + TARGET_FRACTION * (gr - gl)
                # solve: x(t_send) + speed * l_eff = target, in wall clock
                pts_last, x_last = track[-1]
                wall_last = cap.pts_to_wall(pts_last)
                t_send = wall_last + ((target - x_last) / speed - l_eff_ms) / 1000
                with cap.lock:
                    anchor = cap.clock.anchor
                if anchor is not None:   # pc-v15 vsync-aligned release
                    snapped = snap_to_grid(t_send, anchor)
                    align_off_ms = round((snapped - t_send) * 1000, 1)
                    t_send = snapped
                now = time.monotonic()
                if t_send <= now + 0.004:   # due (or overdue): send
                    t_release = inj.release()
                    t_send = t_release
                    x_at_send = x_last + speed * (t_release - wall_last) * 1000
                    break
                elif t_send - now < 0.040:  # close: sleep precisely then send
                    time.sleep(max(0, t_send - time.monotonic()))
                    t_release = inj.release()
                    t_send = t_release
                    x_at_send = x_last + speed * (t_release - wall_last) * 1000
                    break
        time.sleep(0.002)
    else:
        inj.release()   # timeout: defensive release
        return {"ok": False, "reason": "timeout", "armed": armed,
                "n_track": len(track), "n_zones": len(zones)}

    snap("release")
    # post-release: watch cursor come to rest (up to 800ms)
    rest = None
    t0 = time.monotonic()
    last = []
    while time.monotonic() - t0 < 0.8:
        with cap.lock:
            new = cap.samples[start_idx:]
            start_idx = len(cap.samples)
        for pts_ms, arrival, cursor, zone in new:
            if cursor is not None:
                last.append((pts_ms, cursor))
        if len(last) >= 3 and abs(last[-1][1] - last[-3][1]) <= 2:
            rest = last[-1][1]
            snap("rest")
            break
        time.sleep(0.01)

    gl, gr = (np.median([z[0] for z in zones[-5:]]), np.median([z[1] for z in zones[-5:]])) if zones else (None, None)
    success = rest is not None and gl is not None and gl <= rest <= gr
    # signed distance from the aim point: + = released late (long), - = early (short)
    err = (rest - (gl + TARGET_FRACTION * (gr - gl))) if (rest is not None and gl is not None) else None
    return {"ok": True, "success": bool(success), "rest_x": rest,
            "err_px": None if err is None else round(err, 1),
            "timing": None if err is None else ("late" if err > 0 else "early"),
            "green": [gl, gr] if gl is not None else None,
            "speed_px_ms": speed, "x_at_send": x_at_send,
            "align_off_ms": align_off_ms,
            "l_eff_used": l_eff_ms if fixed_hold_s is None else None,
            "measured_l_eff": (None if (rest is None or x_at_send is None or not speed)
                               else round((rest - x_at_send) / speed, 1)),
            "n_track": len(track),
            "debug_track": [(round(t, 1), x) for t, x in track] if DEBUG else None,
            "debug_zones": zones[-10:] if DEBUG else None}


class AutoCal:
    """Per-session l_eff tracker: rolling median of recent measured latencies.
    Removes both the session-to-session offset (84 vs 96 vs 118 across days) and
    the within-session drift (+25ms/20 shots) without hand-set flags. Median over
    a window is robust to the 180ms frame-drop outliers — unlike the pc-v12-rejected
    per-shot gain-0.5 controller, which amplified that same noise."""

    def __init__(self, seed, clamp=(40.0, 250.0)):
        self.seed = float(seed)
        self.lo, self.hi = clamp
        self.hist = []

    def l_eff(self):
        if len(self.hist) < AUTOCAL_MIN:
            return self.seed
        med = float(np.median(self.hist[-AUTOCAL_WINDOW:]))
        return min(self.hi, max(self.lo, med))

    def update(self, measured):
        if measured is not None:
            self.hist.append(float(measured))


def snap_to_grid(t_send_s, anchor_ms, phase_ms=RELEASE_PHASE_MS, v_ms=GridClock.V):
    """Snap a send time (monotonic s) to `phase_ms` before the nearest display-frame
    boundary (grid: anchor_ms + n*v_ms, in monotonic ms). The game consumes input on
    its render frames; a constant send phase turns the 0..16.7ms sampling delay into
    a constant absorbed by l_eff, and the snap (<= +-v/2) picks the frame whose
    landing is nearest the target."""
    t_ms = t_send_s * 1000
    k = round((t_ms + phase_ms - anchor_ms) / v_ms)
    return (anchor_ms + k * v_ms - phase_ms) / 1000


def _extrapolate_now(track, speed, cap):
    if not track or not speed:
        return None, None
    pts_last, x_last = track[-1]
    now = time.monotonic()
    wall_last = cap.pts_to_wall(pts_last)
    return x_last + speed * (now - wall_last) * 1000, now


def find_handler(rgb):
    """Locate the ball handler via his 'Name NN%' label: a row of small white text
    components on a dark box. No label = nobody has the ball (in flight / animation).
    Returns (x, y) screen px or None. Validated offline on 40 saved run frames."""
    white = ((rgb[..., 0] > 225) & (rgb[..., 1] > 225) & (rgb[..., 2] > 225)).astype(np.uint8)
    white[:170, :] = 0    # top banners + meter bar
    white[900:, :] = 0    # bottom banner + player card
    n, _, stats, cents = cv2.connectedComponentsWithStats(white, 8)
    comps = [(float(cents[i][0]), float(cents[i][1])) for i in range(1, n)
             if 3 <= stats[i, cv2.CC_STAT_AREA] <= 300
             and stats[i, cv2.CC_STAT_WIDTH] <= 40 and 4 <= stats[i, cv2.CC_STAT_HEIGHT] <= 28]
    best, best_n = None, 0
    for sx, sy in comps:
        row = sorted(c for c in comps if abs(c[1] - sy) <= 8)
        chains, chain = [], [row[0]]
        for c in row[1:]:
            if c[0] - chain[-1][0] < 35:
                chain.append(c)
            else:
                chains.append(chain)
                chain = [c]
        chains.append(chain)
        for ch in chains:
            if len(ch) >= 7 and 80 <= ch[-1][0] - ch[0][0] <= 400 and len(ch) > best_n:
                x0, x1 = int(ch[0][0]) - 6, int(ch[-1][0]) + 6
                y0, y1 = int(min(c[1] for c in ch)) - 10, int(max(c[1] for c in ch)) + 10
                box = rgb[max(y0, 0):y1, max(x0, 0):x1].astype(np.int32)
                # text sits on a dark navy box; white court lines / logos don't
                if box.size and (box.sum(axis=2) < 330).mean() >= 0.30:
                    best, best_n = (float(np.mean([c[0] for c in ch])), sy), len(ch)
    return best


def wait_shootable(cap, inj, timeout_s=12.0):
    """Block until the ball handler is settled clear of the paint; if he's under
    the rim, pass him out (rate-limited to one tap per ball flight). Replaces the
    blind-timed resets (pc-v6..v8): pressing near the rim dunks, and a made dunk
    puts the ball right back under the rim — an unbreakable loop without vision.
    Returns handler (x, y), or None on timeout (caller shoots blind as before)."""
    t0 = time.monotonic()
    last_pass = -9.0
    prev = None
    while time.monotonic() - t0 < timeout_s:
        cap.ensure()   # restart a dead/aging stream before trusting last_rgb
        rgb = cap.last_rgb
        pos = find_handler(rgb) if rgb is not None else None
        if pos and pos[0] >= HANDLER_SAFE_X:
            if prev and abs(pos[0] - prev[0]) < 40 and abs(pos[1] - prev[1]) < 40:
                return pos   # two consecutive sightings ~settled, not mid-drive
            prev = pos
        else:
            prev = None
            if pos and time.monotonic() - last_pass > BALL_FLIGHT_S + 0.7:
                inj.tap_pass()   # handler near rim: move the ball, then re-look
                last_pass = time.monotonic()
        time.sleep(0.15)
    return None


def serve(cap, inj, l_eff, logf, label, cal=None):
    """Phone-button trigger via logcat: the app logs 'AUTO tapped' on every tap;
    we stream `adb logcat` and fire one shot per tap line. Chosen over HTTP +
    adb reverse because Windows->WSL2 localhost forwarding is broken on this
    machine (probed 2026-07-13: phone->Windows ok, Windows->WSL 000). Uses only
    the adb channel that everything else already relies on."""
    import select
    busy = threading.Lock()

    def watchdog():
        # keep the stream young while idle so a tap never lands on a mid-restart
        # or about-to-die stream (restart takes ~2s and its first frames are junk)
        while True:
            time.sleep(10)
            if busy.acquire(blocking=False):
                try:
                    cap.ensure(max_age_s=120)
                except Exception as e:
                    print(json.dumps({"event": "watchdog_error", "err": str(e)}))
                finally:
                    busy.release()
    threading.Thread(target=watchdog, daemon=True).start()

    subprocess.run([ADB, "logcat", "-c"],   # drop history: only react to fresh taps
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    lc = subprocess.Popen([ADB, "logcat", "-s", "HoopLandHelper"],
                          stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL, text=True)
    print("watching for AUTO taps — tap the overlay button on the phone (Ctrl+C to stop)")
    n = 0
    while True:
        line = lc.stdout.readline()
        if not line:
            raise RuntimeError("logcat stream ended — is the device still connected?")
        if "AUTO tapped" not in line:
            continue
        with busy:
            inj.rot = device_rotation()
            cap.ensure()
            res = take_shot(cap, inj, cal.l_eff() if cal else l_eff)
            if cal:
                cal.update(res.get("measured_l_eff"))
                res["l_eff_auto"] = round(cal.l_eff(), 1)
            res.update(shot=n, label=label, mode="serve")
            n += 1
            out = json.dumps(res)
            print(out)
            logf.write(out + "\n"), logf.flush()
        # drain taps that queued while the shot was executing (double-taps)
        while select.select([lc.stdout], [], [], 0.3)[0]:
            if not lc.stdout.readline():
                break


# ---------- main ----------

def shot_indices(shots, interactive):
    """Return the shot-number iterator for the selected run mode.

    Interactive runs are deliberately unbounded: ``--shots`` is retained for
    command compatibility, but only applies to non-interactive batch runs.
    """
    return itertools.count() if interactive else range(shots)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--shots", type=int, default=1,
                    help="number of shots in batch mode (ignored with --interactive)")
    ap.add_argument("--calibrate", action="store_true")
    ap.add_argument("--label", default="pc-v1")
    ap.add_argument("--l-eff", type=float, default=L_EFF_MS)
    ap.add_argument("--adapt", action="store_true",
                    help="adjust l_eff each shot from landing error (feedback controller)")
    ap.add_argument("--no-autocal", action="store_false", dest="autocal",
                    help="disable per-session l_eff auto-calibration (rolling median "
                         "of measured latency); use the fixed --l-eff instead")
    ap.add_argument("--debug", action="store_true")
    ap.add_argument("--serve", action="store_true",
                    help="phone-button trigger mode: stream logcat and fire one shot "
                         "per overlay AUTO tap (trigger travels over adb, no network)")
    ap.add_argument("--interactive", action="store_true",
                    help="wait for Enter before each shot and run until Ctrl+C; "
                         "never auto-pass (ignores --shots)")
    ap.add_argument("--save-frames", action="store_true",
                    help="save key stream frames per shot (press/armed/release/rest) — "
                         "frames come from the existing stream, zero extra phone load")
    args = ap.parse_args()
    global DEBUG
    DEBUG = args.debug

    logdir = pathlib.Path(__file__).parent / "logs"
    logdir.mkdir(exist_ok=True)
    logf = open(logdir / f"{args.label}.jsonl", "a")

    push_blobs()
    cap, inj = Capture(), Injector()
    cap.start()
    t0 = time.monotonic()
    while True:
        with cap.lock:
            ready = (bool(cap.samples) and cap.alive
                     and cap._stream_frames >= STREAM_WARMUP_FRAMES)
        if ready:
            break
        if time.monotonic() - t0 > 15:
            # don't die: screen may be off/locked, or the device needs a moment —
            # keep retrying so --serve can be started before picking up the phone
            print("no video frames yet — is the phone screen on and unlocked? retrying...")
            cap.stop()
            cap.start()
            t0 = time.monotonic()
        time.sleep(0.1)
    print("stream up")

    measured = []
    l_eff = args.l_eff
    # autocal off when the experimental per-shot --adapt controller is requested
    cal = AutoCal(args.l_eff) if (args.autocal and not args.adapt) else None
    try:
        if args.serve:
            serve(cap, inj, args.l_eff, logf, args.label, cal=cal)
            return
        for i in shot_indices(args.shots, args.interactive):
            snaps = {} if args.save_frames else None
            if args.interactive:
                # refresh an aging stream NOW, while we're idle at the prompt —
                # restarting after Enter fired shots into an unstable fresh stream.
                # (pc-v16 tried encoder-off-while-idle for thermal relief: latency
                # still drifted +25ms within 20 shots — the drift is game-side,
                # so we keep the stream warm and keep Enter->press instant.)
                cap.ensure(max_age_s=60)
                input(f"[shot {i}] position the play, Enter to shoot... ")
            inj.rot = device_rotation()   # user may rotate between shots (~0.3s, off timing path)
            l_eff = cal.l_eff() if cal else args.l_eff
            # interactive: one attempt, no auto-pass — never fight the user's hands
            for attempt in range(1 if args.interactive else 5):
                if args.interactive:
                    cap.ensure()
                    handler = None
                else:
                    handler = wait_shootable(cap, inj)
                res = take_shot(cap, inj, l_eff,
                                fixed_hold_s=1.2 if args.calibrate else None, snaps=snaps)
                res["handler_x"] = round(handler[0]) if handler else None
                if res.get("reason") != "no_meter":
                    break
            if cal:
                cal.update(res.get("measured_l_eff"))
                res["l_eff_auto"] = round(cal.l_eff(), 1)
            if (args.adapt and res.get("rest_x") is not None and res.get("green")
                    and res.get("speed_px_ms")):
                gl, gr = res["green"]
                err_px = res["rest_x"] - (gl + TARGET_FRACTION * (gr - gl))
                l_eff = float(np.clip(l_eff + 0.5 * err_px / res["speed_px_ms"], 40, 250))
                res["l_eff_next"] = round(l_eff, 1)
            if snaps:
                shotdir = logdir / args.label / f"shot{i:03d}"
                shotdir.mkdir(parents=True, exist_ok=True)
                for old in shotdir.glob("*.png"):
                    old.unlink()   # same-label reruns must not leave stale event frames
                for event, rgb in snaps.items():
                    cv2.imwrite(str(shotdir / f"{event}.png"), rgb[:, :, ::-1])
                res["frames_dir"] = str(shotdir)
            res.update(shot=i, label=args.label, mode="cal" if args.calibrate else "live")
            print(json.dumps(res))
            logf.write(json.dumps(res) + "\n")
            logf.flush()
            if res.get("measured_l_eff") is not None:
                measured.append(res["measured_l_eff"])
            # no blind post-shot reset: the next shot's wait_shootable watches the
            # handler label and passes the ball off the rim only when needed
            time.sleep(1.5)   # let game settle between shots
    except KeyboardInterrupt:
        print("\nstopped by Ctrl+C", flush=True)
    finally:
        inj.release()        # belt & braces
        inj.close()
        cap.stop()
        logf.close()

    if measured:
        print(f"\nmeasured L_eff ms: median {np.median(measured):.0f} "
              f"(n={len(measured)}, values {measured})")
        if args.calibrate:
            print(f"-> run live with: --l-eff {np.median(measured):.0f}")


if __name__ == "__main__":
    main()
