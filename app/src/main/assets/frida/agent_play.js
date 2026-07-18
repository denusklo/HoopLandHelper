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
}, (typeof globalThis !== 'undefined' && globalThis.HOOK_CFG) || {});
const STRENGTH = CFG.strength, POWER_FORCE = CFG.powerForce, TEAM_FILTER = CFG.teamFilter;

const ISPLAYER_OFF = 0x130, POWER_OFF = 0xD8, HANDLE_OFF = 0x98, ARR_FIRST = 0x20, GREEN_OFF = 0x20;

function main() {
  const domain_get = ex('il2cpp_domain_get', 'pointer', []);
  const thread_attach = ex('il2cpp_thread_attach', 'pointer', ['pointer']);
  const get_assemblies = ex('il2cpp_domain_get_assemblies', 'pointer', ['pointer', 'pointer']);
  const asm_get_image = ex('il2cpp_assembly_get_image', 'pointer', ['pointer']);
  const class_from_name = ex('il2cpp_class_from_name', 'pointer', ['pointer', 'pointer', 'pointer']);
  const method_from_name = ex('il2cpp_class_get_method_from_name', 'pointer', ['pointer', 'pointer', 'int']);

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

  const isUser = (pc) => !TEAM_FILTER || (!pc.isNull() && pc.add(ISPLAYER_OFF).readU8() !== 0);

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

  send({ t: 'ready', msg: 'PLAY ON — aim=' + (CFG.aim ? STRENGTH : 'off') +
    ' power=' + (CFG.power ? POWER_FORCE : 'off') + ' freeze=' + (CFG.freeze ? 'on' : 'off') +
    ' team-filter=' + (TEAM_FILTER ? 'your player only' : 'EVERYONE') });
}

setTimeout(() => { try { main(); } catch (e) { send({ t: 'error', msg: '' + e, stack: e.stack }); } }, 300);
