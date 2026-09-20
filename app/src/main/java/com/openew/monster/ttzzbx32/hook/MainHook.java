package com.openew.monster.ttzzbx32.hook;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module for com.openew.monster.ttzzbx32 (Cocos Creator 2.x JSB game).
 *
 * Everything is done by injecting JS into the game's own JS engine (no APK change,
 * no overlay permission, no keyboard/Bluetooth needed):
 *
 * 1. Unlock the built-in DebugCheat WITHOUT breaking login: the game gates
 *    DebugCheat.handleKeyDown behind `if ("debug" != Home.GlobalConfig.inst.channel) return`.
 *    We do NOT overwrite the global channel getter (that would re-route MixLogin to WebLogin
 *    and point serverlist/appconfig at the "outer"/"debug" env, which is why enabling the
 *    module used to fail account login). Instead we wrap window.DebugCheat.handleKeyDown so it
 *    temporarily reports channel="debug" ONLY for the duration of the cheat call, then restores
 *    the real channel. The global channel stays at its real value (e.g. td_openew_qianguo), so
 *    the real SDK login path and server selection keep working.
 *
 * 2. Build an in-game floating UI with pure cc.Node/cc.Graphics:
 *      - a small "功能" dot near the top-left, visible once the game scene is up;
 *      - tapping the dot toggles a CENTERED function menu;
 *      - menu buttons call window.DebugCheat.handleKeyDown(<official keycode>) or
 *        toggle BattleTestManager.inst.isWallSuper directly;
 *      - the "隐藏" item collapses the menu back to the dot.
 *
 * 3. Hero-boost toggle (纯客户端本地修改, 不影响登录/结算上报):
 *      - "英雄强化 开/关" 遍历场上所有法师实体, 把攻击范围 atkRange 用 getter 锁成极大值(全屏),
 *        攻速(entityTimeScaleAdd)用 getter 锁成 100 倍, 技能冷却(skillGhost.cdSkills[].isReady)每帧置 true = 0 CD.
 *      - 用 getter-only 覆盖, 因为 AtkSpeAttribute.refreshData() 每帧会重置 entityTimeScaleAdd,
 *        AtkRangeAttribute.refreshData() 每帧会重置 atkRange; 赋值变 no-op, 读取恒返回强化值.
 *      - 只对己方英雄生效 (mageEntities), 不改变 channel(登录/选服逻辑不受影响).
 *    The UI root is a persistent node; additionally buildUI() re-runs on every scene
 *    launch (via a loadScene hook + EVENT_AFTER_SCENE_LAUNCH) and is guarded by a node
 *    validity check, so the dot always comes back even if the persist-root was dropped.
 *
 * IMPORTANT classloader note: org.cocos2dx.lib.* classes live in the TARGET app's
 * PathClassLoader, NOT the module's. Class.forName() from module code will NOT find
 * them. We therefore resolve the bridge class through lpparam.classLoader and cache
 * the reflected Method.
 *
 * Injection thread safety: Cocos JSB evalString must run on the GL thread. Triggers:
 *   a) Cocos2dxRenderer.onSurfaceCreated afterHookedMethod -- GL thread, and by the
 *      time it returns nativeInit() has booted the JS VM. PRIMARY trigger.
 *   b) piggyback on the game's own bridge evalString calls (best effort: evalString
 *      is a native method so the hook itself may be skipped by the framework);
 *   c) Cocos2dxActivity.onCreate -> runOnGLThread delayed retries.
 * The payload is idempotent (window.__ttzz flag + JS-side retry loop), and the UI
 * node is rebuilt on every scene launch (window.__ttzzRoot validity check + loadScene
 * hook + EVENT_AFTER_SCENE_LAUNCH) so it can never get stuck "gone" after a scene
 * switch. Duplicate or repeated triggers are harmless.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.openew.monster.ttzzbx32";
    private static final String BRIDGE_CLASS = "org.cocos2dx.lib.Cocos2dxJavascriptJavaBridge";
    private static final String COCOS_ACTIVITY = "org.cocos2dx.lib.Cocos2dxActivity";
    private static final String COCOS_RENDERER = "org.cocos2dx.lib.Cocos2dxRenderer";

    private static final AtomicBoolean PAYLOAD_SENT = new AtomicBoolean(false);
    private static volatile boolean insideOwnEval = false;
    private static volatile Object cocosActivity = null;
    private static volatile ClassLoader appClassLoader = null;
    private static volatile Method evalStringMethod = null;

    /**
     * JS payload: unlock DebugCheat + build the floating dot & centered menu.
     * Notes baked into the code:
     *  - every touchable node sets setContentSize (Creator 2.x hit-test uses
     *    contentSize, NOT the drawn Graphics shape);
     *  - the menu uses an adaptive 2-column grid so it fits landscape screens;
     *  - single quotes only, to stay Java-string friendly.
     */
    private static final String PAYLOAD_JS =
            "(function(){"
          + "if (window.__ttzz) return; window.__ttzz = 1;"
          + "var tries = 0;"
          + "function unlockCheat(){"
          + "  try {"
          + "    if (typeof Home === 'undefined' || !Home || !Home.GlobalConfig || !Home.GlobalConfig.inst) return false;"
          + "    var DC = window.DebugCheat;"
          + "    if (!DC || typeof DC.handleKeyDown !== 'function') return false;"
          + "    if (DC.__ttzzUnlocked) return true;"
          + "    var orig = DC.handleKeyDown;"
          + "    DC.__ttzzUnlocked = true;"
          + "    DC.handleKeyDown = function(k){"
          + "      var inst = Home.GlobalConfig.inst;"
          + "      var desc = null;"
          + "      try { desc = Object.getOwnPropertyDescriptor(inst, 'channel'); } catch(e){}"
          + "      var ret;"
          + "      try {"
          + "        Object.defineProperty(inst, 'channel', {configurable:true, get:function(){return 'debug';}, set:function(){}});"
          + "        ret = orig.call(DC, k);"
          + "      } finally {"
          + "        try {"
          + "          if (desc) Object.defineProperty(inst, 'channel', desc);"
          + "          else { try { delete inst.channel; } catch(e){} }"
          + "        } catch(e){}"
          + "      }"
          + "      return ret;"
          + "    };"
          + "    return true;"
          + "  } catch(e){ return false; }"
          + "}"
          + "function fire(k){ try { var DC = window.DebugCheat; if (DC && DC.handleKeyDown) DC.handleKeyDown(k); } catch(e){} }"
          + "function tip(s){ try { if (typeof Core !== 'undefined' && Core.TipsUtils) Core.TipsUtils.showTipsFromCenter(s); } catch(e){} }"
          + "function wallToggle(){"
          + "  try {"
          + "    var BTM = window.BattleTestManager;"
          + "    if (!BTM) { try { BTM = eval('BattleTestManager'); } catch(e){} }"
          + "    if (BTM && BTM.inst) { BTM.inst.isWallSuper = !BTM.inst.isWallSuper; tip('城墙无敌:' + (BTM.inst.isWallSuper ? '开' : '关')); }"
          + "    else tip('城墙无敌不可用');"
          + "  } catch(e){ tip('城墙无敌失败'); }"
          + "}"
          + "var HERO_BOOST = false;"
          + "var BIG_RANGE = null;"
          + "function boostAllHeroes(){"
          + "  try {"
          + "    if (typeof Battle === 'undefined' || !Battle || !Battle.EntityMgr || !Battle.EntityMgr.inst) return;"
          + "    var battle = null;"
          + "    try { battle = Battle.EntityMgr.inst.getBattle(BattleIndex.myBattle); } catch(e){ return; }"
          + "    if (!battle || !battle.mageEntities) return;"
          + "    for (var i = 0; i < battle.mageEntities.length; i++) {"
          + "      var e = battle.mageEntities[i];"
          + "      if (!e) continue;"
          + "      var oc = null;"
          + "      try { oc = e.getComponentKeys(Battle.BaseObjectComponent); } catch(e2){ try { oc = e.getComponent(Battle.BaseObjectComponent); } catch(e3){ continue; } }"
          + "      if (!oc) continue;"
          + "      if (!BIG_RANGE) { try { BIG_RANGE = new (oc.atkRange.constructor)(1000000); } catch(e4){ BIG_RANGE = null; } }"
          + "      try {"
          + "        if (!oc.__ttzzRangeLocked) {"
          + "          Object.defineProperty(oc, 'atkRange', {configurable:true, get:function(){ return BIG_RANGE; }, set:function(){}});"
          + "          oc.__ttzzRangeLocked = true;"
          + "          if (oc.rigidBodyComponent && oc.rigidBodyComponent.changeAttackCollider) oc.rigidBodyComponent.changeAttackCollider();"
          + "        }"
          + "      } catch(e5){}"
          + "      try {"
          + "        if (!e.__ttzzSpdLocked) {"
          + "          Object.defineProperty(e, 'entityTimeScaleAdd', {configurable:true, get:function(){ return 100; }, set:function(){}});"
          + "          e.__ttzzSpdLocked = true;"
          + "        }"
          + "      } catch(e6){}"
          + "      try {"
          + "        var sc = e.getComponent(Battle.SkillComponent);"
          + "        if (sc && sc.skillGhost && sc.skillGhost.cdSkills) {"
          + "          for (var k = 0; k < sc.skillGhost.cdSkills.length; k++) {"
          + "            var cd = sc.skillGhost.cdSkills[k];"
          + "            if (cd && cd.isReady !== undefined) cd.isReady = true;"
          + "            if (cd && cd.attack_range && BIG_RANGE) { try { cd.attack_range = BIG_RANGE; } catch(e7){} }"
          + "          }"
          + "        }"
          + "      } catch(e8){}"
          + "    }"
          + "  } catch(e9){}"
          + "}"
          + "function heroBoostLoop(){"
          + "  if (HERO_BOOST) { try { boostAllHeroes(); } catch(e){} setTimeout(heroBoostLoop, 300); }"
          + "}"
          + "function mkLabel(parent, text, size, color, y){"
          + "  var t = new cc.Node(); var l = t.addComponent(cc.Label);"
          + "  l.string = text; l.fontSize = size; l.lineHeight = size + 4;"
          + "  t.color = color; t.setPosition(0, y || 0); parent.addChild(t); return t;"
          + "}"
          + "function mkBtn(parent, x, y, w, h, text, cb){"
          + "  var n = new cc.Node();"
          + "  var g = n.addComponent(cc.Graphics);"
          + "  g.roundRect(-w/2, -h/2, w, h, 9);"
          + "  g.fillColor = cc.color(255,255,255,230); g.fill();"
          + "  n.setContentSize(w, h);"
          + "  n.setAnchorPoint(0.5, 0.5);"
          + "  mkLabel(n, text, 22, cc.color(30,30,30,255), 0);"
          + "  n.setPosition(x, y);"
          + "  n.on(cc.Node.EventType.TOUCH_END, function(){ try { cb(); } catch(e){} });"
          + "  parent.addChild(n);"
          + "}"
          + "function buildUI(){"
          + "  if (window.__ttzzRoot && window.__ttzzRoot.isValid) return;"
          + "  var scene = null;"
          + "  try { scene = cc.director.getScene(); } catch(e){ return; }"
          + "  if (!scene) return;"
          + "  var size = cc.view.getVisibleSize();"
          + "  var root = new cc.Node('ttzz_root');"
          + "  var menu = new cc.Node('ttzz_menu');"
          + "  var mg = menu.addComponent(cc.Graphics);"
          + "  var items = ["
          + "    ['一键胜利', function(){ fire(cc.macro.KEY.num2); tip('一键胜利'); }],"
          + "    ['跳末波+英雄x100', function(){ fire(cc.macro.KEY.num3); tip('英雄x100'); }],"
          + "    ['收尾布置', function(){ fire(cc.macro.KEY.b); tip('收尾布置'); }],"
          + "    ['城墙无敌 开/关', function(){ wallToggle(); }],"
          + "    ['加速 +0.5', function(){ fire(cc.macro.KEY['+']); }],"
          + "    ['减速 -0.5', function(){ fire(cc.macro.KEY['-']); }],"
          + "    ['战斗石+10', function(){ fire(cc.macro.KEY.num8); }],"
          + "    ['掉落宝箱', function(){ fire(cc.macro.KEY.x); }],"
          + "    ['测试工具窗', function(){ fire(cc.macro.KEY.t); }],"
          + "    ['英雄强化 开/关', function(){ HERO_BOOST = !HERO_BOOST; if (HERO_BOOST){ try { boostAllHeroes(); } catch(e){} heroBoostLoop(); tip('英雄强化:开'); } else tip('英雄强化:关'); }],"
          + "    ['隐藏', function(){ menu.active = false; }]"
          + "  ];"
          + "  var BW = Math.min(560, size.width * 0.8);"
          + "  var CW = (BW - 30) / 2;"
          + "  var RH = 50;"
          + "  var rows = Math.ceil(items.length / 2);"
          + "  var H = rows * RH + 64;"
          + "  mg.roundRect(-BW/2, -H/2, BW, H, 14);"
          + "  mg.fillColor = cc.color(20,20,20,215); mg.fill();"
          + "  mg.strokeColor = cc.color(0,190,255,255); mg.lineWidth = 2; mg.stroke();"
          + "  mkLabel(menu, '功能菜单', 26, cc.color(255,255,255,255), H/2 - 26);"
          + "  var topY = H/2 - 64;"
          + "  for (var i = 0; i < items.length; i++) {"
          + "    var r = Math.floor(i / 2);"
          + "    var c = i % 2;"
          + "    var bx = c === 0 ? -CW/2 - 5 : CW/2 + 5;"
          + "    mkBtn(menu, bx, topY - r*RH - RH/2, CW, 40, items[i][0], items[i][1]);"
          + "  }"
          + "  menu.setPosition(size.width/2, size.height/2);"
          + "  menu.active = false;"
          + "  root.addChild(menu);"
          + "  var dot = new cc.Node('ttzz_dot');"
          + "  var dg = dot.addComponent(cc.Graphics);"
          + "  dg.circle(0,0,28); dg.fillColor = cc.color(255,255,255,200); dg.fill();"
          + "  dg.strokeColor = cc.color(0,190,255,255); dg.lineWidth = 3; dg.stroke();"
          + "  dot.setContentSize(72, 72);"
          + "  dot.setAnchorPoint(0.5, 0.5);"
          + "  mkLabel(dot, '功能', 18, cc.color(30,30,30,255), 0);"
          + "  dot.setPosition(46, size.height - 56);"
          + "  dot.on(cc.Node.EventType.TOUCH_END, function(){ menu.active = !menu.active; });"
          + "  root.addChild(dot);"
          + "  scene.addChild(root);"
          + "  try { cc.game.addPersistRootNode(root); } catch(e){}"
          + "  window.__ttzzRoot = root; window.__ttzzMenu = menu; window.__ttzzDot = dot;"
          + "}"
          + "function tick(){"
          + "  tries++;"
          + "  var ok = unlockCheat();"
          + "  if (ok) window.__ttzzCh = 1;"
          + "  var sceneOk = false;"
          + "  try { sceneOk = (typeof cc !== 'undefined' && cc.director && cc.director.getScene && cc.director.getScene() && cc.view && cc.view.getVisibleSize); } catch(e){}"
          + "  if (sceneOk) { try { buildUI(); } catch(e){} }"
          + "  if (window.__ttzzCh) { setTimeout(tick, 1500); return; }"
          + "  if (tries < 1200) setTimeout(tick, 250);"
          + "}"
          + "function watchScenes(){"
          + "  try {"
          + "    if (window.__ttzzWatch) return; window.__ttzzWatch = 1;"
          + "    var d = cc.director; if (!d) return;"
          + "    var _ls = d.loadScene;"
          + "    if (typeof _ls === 'function') { d.loadScene = function(){ var a = arguments; var r = _ls.apply(d, a); setTimeout(function(){ try { buildUI(); } catch(e){} }, 400); return r; }; }"
          + "    var _rs = d.runScene;"
          + "    if (typeof _rs === 'function') { d.runScene = function(){ var a = arguments; var r = _rs.apply(d, a); setTimeout(function(){ try { buildUI(); } catch(e){} }, 400); return r; }; }"
          + "    var ev = (cc.Director && cc.Director.EVENT_AFTER_SCENE_LAUNCH) || (d.EVENT_AFTER_SCENE_LAUNCH);"
          + "    if (d.on && ev) { d.on(ev, function(){ setTimeout(function(){ try { buildUI(); } catch(e){} }, 400); }); }"
          + "  } catch(e){}"
          + "}"
          + "watchScenes();"
          + "tick();"
          + "})();";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        appClassLoader = lpparam.classLoader;
        evalStringMethod = resolveEvalString(appClassLoader);
        XposedBridge.log("[TTZZBX32] loaded into " + lpparam.packageName
                + "; evalString resolved=" + (evalStringMethod != null));

        // (a) PRIMARY: Cocos2dxRenderer.onSurfaceCreated -- GL thread; nativeInit()
        // runs main.js inside this call, so the JS VM is ready right after.
        try {
            XposedHelpers.findAndHookMethod(COCOS_RENDERER, lpparam.classLoader,
                    "onSurfaceCreated", GL10.class, EGLConfig.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // a fresh surface may mean a freshly (re)initialized engine:
                            // allow re-injection even if we already injected before.
                            PAYLOAD_SENT.set(false);
                            evalPayload("onSurfaceCreated");
                        }
                    });
            XposedBridge.log("[TTZZBX32] renderer hook installed: " + COCOS_RENDERER);
        } catch (Throwable t) {
            XposedBridge.log("[TTZZBX32] renderer hook FAILED: " + t);
        }

        // (b) Best effort: piggyback on the game's own bridge calls. evalString is a
        // native method; the framework may refuse to hook it -- purely optional.
        try {
            XposedHelpers.findAndHookMethod(BRIDGE_CLASS, lpparam.classLoader,
                    "evalString", String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (insideOwnEval || PAYLOAD_SENT.get()) {
                                return;
                            }
                            evalPayload("evalString-piggyback");
                        }
                    });
            XposedBridge.log("[TTZZBX32] bridge hook installed: " + BRIDGE_CLASS);
        } catch (Throwable t) {
            XposedBridge.log("[TTZZBX32] bridge hook skipped/failed: " + t);
        }

        // (c) Activity created -> remember it, retry injection via runOnGLThread.
        try {
            XposedHelpers.findAndHookMethod(COCOS_ACTIVITY, lpparam.classLoader,
                    "onCreate", Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            cocosActivity = param.thisObject;
                            scheduleRetries();
                        }
                    });
            XposedBridge.log("[TTZZBX32] activity hook installed: " + COCOS_ACTIVITY);
        } catch (Throwable t) {
            XposedBridge.log("[TTZZBX32] activity hook FAILED: " + t);
        }

        // Periodic re-injection watchdog. The Cocos JS engine can be cleaned up and
        // re-initialized at runtime (ScriptEngine::cleanup), which wipes window.__ttzz
        // and our UI node. The payload is idempotent (window.__ttzz guard), so re-injecting
        // on a live engine is a safe no-op, and re-injecting after a reset rebuilds the UI.
        scheduleWatchdog();
    }

    /** Resolve Cocos2dxJavascriptJavaBridge.evalString via the app's classloader. */
    private static Method resolveEvalString(ClassLoader cl) {
        try {
            Class<?> bridge = Class.forName(BRIDGE_CLASS, true, cl);
            Method m = bridge.getMethod("evalString", String.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            XposedBridge.log("[TTZZBX32] resolveEvalString failed: " + t);
            return null;
        }
    }

    /** Delayed retry attempts via runOnGLThread, in case (a) fired too early. */
    private static void scheduleRetries() {
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() { injectViaActivity(); }
        }, 4000);
        h.postDelayed(new Runnable() {
            @Override public void run() { injectViaActivity(); }
        }, 10000);
        h.postDelayed(new Runnable() {
            @Override public void run() { injectViaActivity(); }
        }, 18000);
    }

    private static void injectViaActivity() {
        if (PAYLOAD_SENT.get()) {
            return;
        }
        Object act = cocosActivity;
        if (act == null) {
            return;
        }
        try {
            Method run = act.getClass().getMethod("runOnGLThread", Runnable.class);
            run.invoke(act, new Runnable() {
                @Override public void run() { evalPayload("runOnGLThread"); }
            });
        } catch (Throwable t) {
            // Last resort: direct eval (harmless if the VM is not ready).
            evalPayload("direct-fallback");
        }
    }

    /** Periodic re-injection watchdog (see scheduleWatchdog()). */
    private static volatile boolean watchdogStarted = false;
    private static void scheduleWatchdog() {
        if (watchdogStarted) {
            return;
        }
        watchdogStarted = true;
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                // allow re-injection into a (possibly re-initialized) engine; the payload
                // is idempotent so this is a no-op while the engine is alive, and rebuilds
                // the UI after a ScriptEngine::cleanup wiped window.__ttzz.
                PAYLOAD_SENT.set(false);
                injectViaActivity();
                h.postDelayed(this, 2000);
            }
        }, 2000);
    }

    /**
     * Evaluate the payload once, on the caller's thread (callers guarantee GL thread).
     * The payload itself is idempotent, so repeat triggers are safe.
     */
    private static void evalPayload(String via) {
        if (PAYLOAD_SENT.get()) {
            return;
        }
        try {
            Method m = evalStringMethod;
            if (m == null) {
                m = resolveEvalString(appClassLoader);
                if (m == null) {
                    XposedBridge.log("[TTZZBX32] evalString unavailable at " + via);
                    return;
                }
                evalStringMethod = m;
            }
            insideOwnEval = true;
            try {
                m.invoke(null, PAYLOAD_JS);
            } finally {
                insideOwnEval = false;
            }
            PAYLOAD_SENT.set(true);
            XposedBridge.log("[TTZZBX32] payload injected via " + via
                    + " (dot UI + DebugCheat.handleKeyDown wrapped; real channel preserved)");
        } catch (Throwable t) {
            XposedBridge.log("[TTZZBX32] payload inject via " + via + " failed: " + t);
        }
    }
}
