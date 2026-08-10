// FINAL play agent: aim-assist + cursor-freeze + power-force, gated to ACTIVE shots
// taken by YOUR player (PlayerController.isPlayer @ 0x130). CPU shots untouched.
// Tunables below. Logs the first few shots' isPlayer so the team filter is verifiable.
// Reversible on detach.
'use strict';
const IL2CPP = Process.getModuleByName('libil2cpp.so');
const ex = (n, r, a) => new NativeFunction(IL2CPP.getExportByName(n), r, a);

// Config: defaults here, overridden by globalThis.HOOK_CFG that play.py injects
// from CLI flags. Aim-assist is the core feature; power + freeze are opt-in.
const CFG = Object.assign({
  aim: true,          // aim-assist (the make lever)
  strength: 0.97,     // aim pull toward hoop (1=dead-on)
  power: false,       // force energy at release (hold time no longer matters)
  powerForce: 0.45,
  freeze: false,      // cosmetic: park the visual cursor on green
  teamFilter: true,   // only your player (false = everyone, debug)
  aimTeam: false,     // aim-assist gate: false = controlled avatar (isPlayer); true = whole team (ourSet, isPlayer-locked)
}, (typeof globalThis !== 'undefined' && globalThis.HOOK_CFG) || {});
const STRENGTH = CFG.strength, POWER_FORCE = CFG.powerForce, TEAM_FILTER = CFG.teamFilter;
const AIM_TEAM = CFG.aimTeam;

const ISPLAYER_OFF = 0x130, POWER_OFF = 0xD8, HANDLE_OFF = 0x98, ARR_FIRST = 0x20, GREEN_OFF = 0x20;

function main() {
  const domain_get = ex('il2cpp_domain_get', 'pointer', []);
  const thread_attach = ex('il2cpp_thread_attach', 'pointer', ['pointer']);
  const get_assemblies = ex('il2cpp_domain_get_assemblies', 'pointer', ['pointer', 'pointer']);
  const asm_get_image = ex('il2cpp_assembly_get_image', 'pointer', ['pointer']);
  const class_from_name = ex('il2cpp_class_from_name', 'pointer', ['pointer', 'pointer', 'pointer']);
  const method_from_name = ex('il2cpp_class_get_method_from_name', 'pointer', ['pointer', 'pointer', 'int']);
  const class_get_type = ex('il2cpp_class_get_type', 'pointer', ['pointer']);
  const type_get_object = ex('il2cpp_type_get_object', 'pointer', ['pointer']);

  const domain = domain_get();
  thread_attach(domain);
  const sizePtr = Memory.alloc(Process.pointerSize);
  const asms = get_assemblies(domain, sizePtr);
  const n = sizePtr.readULong();
  const resolveNs = (ns, name) => {
    const nsp = Memory.allocUtf8String(ns), nm = Memory.allocUtf8String(name);
    for (let i = 0; i < n; i++) {
      const k = class_from_name(asm_get_image(asms.add(i * Process.pointerSize).readPointer()), nsp, nm);
      if (!k.isNull()) return k;
    }
    return NULL;
  };
  const bball = resolveNs('', 'Basketball');
  const shotBar = resolveNs('', 'ShotBar');
  const av = resolveNs('', 'AttributeValues');
  const tf = resolveNs('UnityEngine', 'Transform');
  const T = (nm, ac) => method_from_name(tf, Memory.allocUtf8String(nm), ac);
  const getPos = new NativeFunction(T('get_position', 0).readPointer(), ['float', 'float', 'float'], ['pointer', 'pointer']);
  const getLocal = new NativeFunction(T('get_localPosition', 0).readPointer(), ['float', 'float', 'float'], ['pointer', 'pointer']);
  const mSetLocal = T('set_localPosition', 1);
  const setLocal = new NativeFunction(mSetLocal.readPointer(), 'void', ['pointer', ['float', 'float', 'float'], 'pointer']);
  const getParent = new NativeFunction(T('get_parent', 0).readPointer(), 'pointer', ['pointer', 'pointer']);
  const mInv = T('InverseTransformPoint', 1);
  const invPt = new NativeFunction(mInv.readPointer(), ['float', 'float', 'float'], ['pointer', ['float', 'float', 'float'], 'pointer']);

  // ---- optional whole-team roster (only built when aimTeam) ----
  // Our team = the team of the controlled avatar (isPlayer). Auto-detected and locked for the match;
  // a new match (new TeamManager objects) re-locks. Adapted from agent_autoplay.js refreshOurTeam.
  const PS = Process.pointerSize;
  const ObjectC = resolveNs('UnityEngine', 'Object');
  const PlayerManager = resolveNs('', 'PlayerManager');
  const mFind = method_from_name(ObjectC, Memory.allocUtf8String('FindObjectOfType'), 2);
  const findObjectOfType = new NativeFunction(mFind.readPointer(), 'pointer', ['pointer', 'int', 'pointer']);
  const pmType = type_get_object(class_get_type(PlayerManager));
  // Do NOT cache PlayerManager: a new match reloads the scene and destroys it (dead pointer isn't null).
  const getPM = () => { try { return findObjectOfType(pmType, 0, mFind); } catch (e) { return NULL; } };
  const readList = (mgr, off) => {
    try {
      if (!mgr || mgr.isNull()) return [];
      const lst = mgr.add(off).readPointer(); if (lst.isNull()) return [];
      const arr = lst.add(0x10).readPointer(); const size = lst.add(0x18).readS32();
      if (arr.isNull() || size <= 0 || size > 32) return [];
      const out = []; for (let i = 0; i < size; i++) { const p = arr.add(0x20 + i * PS).readPointer(); if (!p.isNull()) out.push(p); } return out;
    } catch (e) { return []; }
  };
  const teamOf = (pc) => { try { return (!pc || pc.isNull()) ? NULL : pc.add(0x38).readPointer(); } catch (e) { return NULL; } };
  let ourSet = new Set(), ourTeamMgr = null, lastSide = 'x';
  // Side-file write via libc: under an eternalized QuickJS agent (frida-inject -e) the Frida `File`
  // API is unreliable, and the agent runs as the game uid, so it writes the (chmod 666) file directly.
  const libc = Process.getModuleByName('libc.so');
  const fopen = new NativeFunction(libc.getExportByName('fopen'), 'pointer', ['pointer', 'pointer']);
  const fwrite = new NativeFunction(libc.getExportByName('fwrite'), 'ulong', ['pointer', 'ulong', 'ulong', 'pointer']);
  const fclose = new NativeFunction(libc.getExportByName('fclose'), 'int', ['pointer']);
  const fread = new NativeFunction(libc.getExportByName('fread'), 'ulong', ['pointer', 'ulong', 'ulong', 'pointer']);
  const TEAM_PATH = Memory.allocUtf8String('/data/local/tmp/.hoop_team');
  const MODE_W = Memory.allocUtf8String('w');
  // Live aim-mode toggle: the app writes 'team'/'player' to this file; the eternalized agent re-reads
  // it (no re-inject needed) so isUser can switch between whole-team and controlled-avatar at runtime.
  const AIMMODE_PATH = Memory.allocUtf8String('/data/local/tmp/.hoop_aimmode');
  const MODE_R = Memory.allocUtf8String('r');
  let aimTeamLive = AIM_TEAM;
  const readAimMode = () => {
    try {
      const f = fopen(AIMMODE_PATH, MODE_R);
      if (f.isNull()) return;
      const buf = Memory.alloc(16);
      const n = fread(buf, 1, 15, f).toNumber();
      fclose(f);
      if (n > 0) aimTeamLive = buf.readUtf8String(n).indexOf('team') === 0;
    } catch (e) {}
  };
  // Publish the detected side (home/road/null) to a side file the app polls, and send it. Change-only.
  const writeSide = (side) => {
    if (side === lastSide) return;
    lastSide = side;
    try {
      const f = fopen(TEAM_PATH, MODE_W);
      if (!f.isNull()) {
        // non-empty marker even when unlocked: a written 'detecting' proves refreshOurTeam ran (vs an
        // empty pre-created file = detection never ran). App maps anything != home/road to detecting.
        const s = (side === 'home' || side === 'road') ? side : 'detecting';
        fwrite(Memory.allocUtf8String(s), 1, s.length, f);
        fclose(f);
      }
    } catch (e) {}
    send({ t: 'team', side });
  };
  // Detection runs ALWAYS (so the UI can show the side in either mode); the AIM gate uses ourSet only
  // when AIM_TEAM. null side => "detecting" (no controlled avatar locked yet / cold sim / between matches).
  const refreshOurTeam = () => {
    try {
      const mgr = getPM(); if (mgr.isNull()) { writeSide(null); return; }
      const home = readList(mgr, 0x88), road = readList(mgr, 0x90);
      const all = home.concat(road);
      if (all.length === 0) { writeSide(null); return; }
      for (const pc of all) { if (!pc.isNull() && pc.add(ISPLAYER_OFF).readU8() !== 0) { ourTeamMgr = teamOf(pc); break; } }
      if (ourTeamMgr && !all.some((pc) => teamOf(pc).equals(ourTeamMgr))) ourTeamMgr = null; // stale (new match)
      if (!ourTeamMgr) { writeSide(null); return; }   // no controlled avatar seen yet -> cannot auto-detect (cold sim)
      ourSet = new Set(all.filter((pc) => teamOf(pc).equals(ourTeamMgr)).map((pc) => pc.toString()));
      const side = home.some((pc) => teamOf(pc).equals(ourTeamMgr)) ? 'home'
                 : road.some((pc) => teamOf(pc).equals(ourTeamMgr)) ? 'road' : null;
      writeSide(side);
    } catch (e) {}
  };
  setInterval(() => { refreshOurTeam(); readAimMode(); }, 500);   // timer-driven (survives eternalize); no per-frame cost

  const isUser = (pc) => {
    if (!TEAM_FILTER) return true;                              // debug: everyone
    if (pc.isNull()) return false;
    if (aimTeamLive) return ourSet.has(pc.toString());          // our whole team (isPlayer-locked); live-toggled
    return pc.add(ISPLAYER_OFF).readU8() !== 0;                 // controlled avatar only
  };

  // ---- aim-assist (arg0 = shooting PlayerController) ----
  if (CFG.aim) {
    const aimFp = method_from_name(av, Memory.allocUtf8String('GetShotAccuracy'), 3).readPointer();
    const origAim = new NativeFunction(aimFp, ['float', 'float', 'float'], ['pointer', 'int', 'pointer', 'pointer']);
    Interceptor.replace(aimFp, new NativeCallback(function (pc, t, tr, mi) {
      const aim = origAim(pc, t, tr, mi);
      if (tr.isNull() || !isUser(pc)) return aim;
      const g = getPos(tr, T('get_position', 0));
      return [aim[0] + (g[0] - aim[0]) * STRENGTH,
              aim[1] + (g[1] - aim[1]) * STRENGTH,
              aim[2] + (g[2] - aim[2]) * STRENGTH];
    }, ['float', 'float', 'float'], ['pointer', 'int', 'pointer', 'pointer']));
  }

  // ---- shot gating (ShotBegan(this, shooterPlayerController, shotType)) ----
  let sbInst = null, active = false, userShot = false, logs = 0;
  const sbEnable = method_from_name(shotBar, Memory.allocUtf8String('OnEnable'), 0);
  if (!sbEnable.isNull()) Interceptor.attach(sbEnable.readPointer(), { onEnter(a) { sbInst = a[0]; } });
  Interceptor.attach(method_from_name(bball, Memory.allocUtf8String('ShotBegan'), 2).readPointer(), {
    onEnter(a) {
      active = true;
      userShot = isUser(a[1]);
      if (logs++ < 6) send({ t: 'shot', userShot: userShot, assisted: userShot });
    },
  });
  for (const [nm, ac] of [['ShotReleased', 1], ['LayupReleased', 1], ['FloaterReleased', 1], ['HookShotReleased', 1], ['DunkReleased', 0]]) {
    const m = method_from_name(bball, Memory.allocUtf8String(nm), ac);
    if (!m.isNull()) Interceptor.attach(m.readPointer(), {
      onEnter(a) {
        if (CFG.power && userShot && !a[0].isNull()) a[0].add(POWER_OFF).writeFloat(POWER_FORCE);
        active = false;
      },
    });
  }

  // Freeze cursor-move is opt-in: attach the per-frame Update hook ONLY when freeze is on, so normal
  // play pays no per-frame JS cost. Team/aim detection is timer-driven (setInterval above).
  if (CFG.freeze) {
    const handle = (inst) => {
      const arr = inst.add(HANDLE_OFF).readPointer();
      if (arr.isNull() || arr.add(0x18).readU32() < 1) return null;
      const h0 = arr.add(ARR_FIRST).readPointer();
      return h0.isNull() ? null : h0;
    };
    Interceptor.attach(method_from_name(bball, Memory.allocUtf8String('Update'), 0).readPointer(), {
      onEnter(a) { this.self = a[0]; },
      onLeave() {
        if (!active || !userShot || sbInst === null) return;
        const green = sbInst.add(GREEN_OFF).readPointer();
        const h = handle(this.self);
        if (green.isNull() || h === null) return;
        const parent = getParent(h, T('get_parent', 0));
        if (parent.isNull()) return;
        const gw = getPos(green, T('get_position', 0));
        const lt = invPt(parent, [gw[0], gw[1], gw[2]], mInv);
        const hl = getLocal(h, T('get_localPosition', 0));
        if (Math.abs(hl[0] - lt[0]) > 0.05) setLocal(h, [lt[0], hl[1], hl[2]], mSetLocal);
      },
    });
  }

  readAimMode(); refreshOurTeam();   // immediate first read/attempt at load (before eternalize)
  send({ t: 'ready', msg: 'PLAY ON — aim=' + (CFG.aim ? STRENGTH : 'off') +
    ' power=' + (CFG.power ? POWER_FORCE : 'off') + ' freeze=' + (CFG.freeze ? 'on' : 'off') +
    ' team-filter=' + (TEAM_FILTER ? (AIM_TEAM ? 'whole team (isPlayer-locked)' : 'your player only') : 'EVERYONE') +
    ' aimTeam=' + (AIM_TEAM ? 'on' : 'off') });
}

setTimeout(() => { try { main(); } catch (e) { send({ t: 'error', msg: '' + e, stack: e.stack }); } }, 300);
