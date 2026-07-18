#!/usr/bin/env python3
"""Minimal self-checks for shoot.py's pure logic. Run: .venv/bin/python test_shoot.py"""
import numpy as np

import shoot


def test_analyze_row():
    w = 463
    row = np.zeros((w, 3), np.uint8)                      # black meter bg
    row[140:205] = (229, 112, 40)                          # orange segment
    row[205:235] = (46, 106, 66)                           # dark green zone (measured in-game)
    row[300] = (255, 255, 255)                             # white cursor
    cursor, zone = shoot.analyze_row(row)
    assert cursor == 300, cursor
    assert zone == (205, 234), zone

    # no cursor: nothing bright enough
    row[300] = (0, 0, 0)
    cursor, zone = shoot.analyze_row(row)
    assert cursor is None

    # A bright court line in the same screen row must not masquerade as a
    # cursor when the shooting meter is absent.
    inactive = np.full((w, 3), (180, 120, 70), np.uint8)  # court-colored background
    inactive[300] = (255, 255, 255)     # unrelated bright line
    cursor, zone = shoot.analyze_row(inactive)
    assert cursor is None and zone is None


def test_analyze_row_orange_fallback():
    w = 463
    # Hard shot: orange-only meter, no green segment -> aim the wide orange band.
    row = np.zeros((w, 3), np.uint8)
    row[140:266] = (229, 112, 40)                          # 126px orange band, no green
    row[300] = (255, 255, 255)                             # white cursor
    cursor, zone = shoot.analyze_row(row)
    assert cursor == 300, cursor
    assert zone is not None and 140 <= zone[0] <= 145 and 260 <= zone[1] <= 266, zone
    assert zone[1] - zone[0] > shoot.GREEN_MAX_RUN, zone   # wide band, not a green run

    # Green present -> green still wins (orange flanks are not the target).
    row = np.zeros((w, 3), np.uint8)
    row[140:205] = (229, 112, 40)                          # left orange flank
    row[205:235] = (46, 106, 66)                           # green sweet-spot
    row[235:270] = (229, 112, 40)                          # right orange flank
    row[300] = (255, 255, 255)
    _, zone = shoot.analyze_row(row)
    assert zone == (205, 234), zone

    # Court-floor bleed (full-width orange) must not become a target zone.
    floor = np.full((w, 3), (200, 150, 90), np.uint8)
    cursor, zone = shoot.analyze_row(floor)
    assert zone is None, zone


def test_best_run_gap_merge_and_bounds():
    mask = np.zeros(100, bool)
    mask[10:20] = True; mask[22:30] = True                 # gap 2 <= 4: merged
    assert shoot.best_run(mask) == (10, 29)
    mask = np.zeros(100, bool)
    mask[50:53] = True                                     # width 3 < GREEN_MIN_RUN
    assert shoot.best_run(mask) is None


def test_grid_clock_burst_recovery():
    c = shoot.GridClock()
    v = c.V
    # on-time, on-time, burst (+40ms late, then +2ms), then on-time again
    ts = [1000.0, 1000 + v, 1000 + 2 * v + 40, 1000 + 2 * v + 42,
          1000 + 5 * v, 1000 + 6 * v, 1000 + 7 * v]
    est = [c.add(t) for t in ts]
    assert abs(est[0] - 1000.0) < 1e-6                    # min-latency anchor
    assert abs((est[1] - est[0]) - v) < 1e-6              # on-time cadence exact
    assert all(e2 > e1 for e1, e2 in zip(est, est[1:]))   # strictly monotonic
    assert all(e <= t + 1e-6 for e, t in zip(est, ts))    # never later than arrival
    assert all(t - e < 60 for e, t in zip(est, ts))       # bounded staleness
    # post-burst on-time frames recover exact vsync cadence
    assert abs((est[-1] - est[-2]) - v) < 1e-6, est


def test_stable_zone():
    assert shoot.stable_zone([(200, 230)] * 2) is None            # too few
    assert shoot.stable_zone([(200, 230), (201, 231), (199, 229)]) == (200, 230)
    # outlier zone doesn't poison the median
    gl, gr = shoot.stable_zone([(200, 230), (325, 333), (201, 231), (199, 229)])
    assert 199 <= gl <= 201 and 229 <= gr <= 231


def test_fit_speed_bounds():
    t = list(range(0, 8 * 17, 17))
    good = [(ti, 100 + 0.45 * ti) for ti in t]
    assert abs(shoot.fit_speed(good) - 0.45) < 0.01
    crazy = [(ti, 100 + 3.0 * ti) for ti in t]              # 3 px/ms: out of bounds
    assert shoot.fit_speed(crazy) is None
    assert shoot.fit_speed(good[:3]) is None                # below MIN_SPEED_SAMPLES

    # One monotonic but bogus argmax must not tilt the fit.
    contaminated = good.copy()
    contaminated[3] = (contaminated[3][0], contaminated[3][1] + 20)
    assert abs(shoot.fit_speed(contaminated) - 0.45) < 0.01


def test_autocal():
    c = shoot.AutoCal(seed=96.0)
    assert c.l_eff() == 96.0                       # seed until AUTOCAL_MIN samples
    c.update(100); c.update(110)
    assert c.l_eff() == 96.0                        # still < min
    c.update(120)
    assert c.l_eff() == 110.0                       # median(100,110,120)
    # a single frame-drop outlier must not swing the median much
    c.update(400)
    assert 105 <= c.l_eff() <= 120, c.l_eff()
    # None (blind/no-meter shot) is ignored
    before = c.l_eff()
    c.update(None)
    assert c.l_eff() == before
    # clamp holds
    c2 = shoot.AutoCal(seed=96.0, clamp=(40, 130))
    for v in (300, 300, 300):
        c2.update(v)
    assert c2.l_eff() == 130.0


def test_snap_to_grid():
    v = shoot.GridClock.V
    anchor = 5000.0
    # any raw send snaps to exactly phase_ms before some boundary, moving <= v/2
    for raw_ms in (5100.0, 5103.3, 5108.9, 5111.11, 5116.6):
        snapped = shoot.snap_to_grid(raw_ms / 1000, anchor, phase_ms=6.0) * 1000
        assert abs(snapped - raw_ms) <= v / 2 + 1e-9, (raw_ms, snapped)
        rem = (snapped + 6.0 - anchor) % v
        assert min(rem, v - rem) < 1e-6, (raw_ms, snapped, rem)


def test_shot_indices():
    assert list(shoot.shot_indices(3, interactive=False)) == [0, 1, 2]
    infinite = shoot.shot_indices(20, interactive=True)
    assert [next(infinite) for _ in range(4)] == [0, 1, 2, 3]


def test_capture_samples_never_trimmed():
    # regression: `del samples[:-600]` pinned len() at 600, so take_shot's
    # index-based reads returned [] forever ~30s into every run (pc-v9 root cause)
    import inspect
    assert "del self.samples" not in inspect.getsource(shoot.Capture)


def test_find_handler_on_saved_frames():
    import pathlib
    import cv2
    logs = pathlib.Path(__file__).parent / "logs"
    cases = [  # (frame, expected x-range or None) — ground truth eyeballed from frames
        ("pc-v8/shot002/press.png", (880, 960)),    # C. Joseph holding at the rim
        ("pc-v8/shot004/press.png", (810, 890)),    # L. Chambers, ball in the net
        ("pc-v7/shot003/press.png", None),          # ball in pass flight: no handler
        ("pc-v7/shot002/press.png", (1150, 1220)),  # clean meter shot, clear of paint
    ]
    ran = 0
    for rel, want in cases:
        p = logs / rel
        if not p.exists():
            continue
        got = shoot.find_handler(cv2.imread(str(p))[:, :, ::-1])
        if want is None:
            assert got is None, (rel, got)
        else:
            assert got is not None and want[0] <= got[0] <= want[1], (rel, got)
        ran += 1
    assert ran, "no saved frames found — run with --save-frames first"


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_"):
            fn()
            print(f"{name} OK")
    print("all checks passed")
