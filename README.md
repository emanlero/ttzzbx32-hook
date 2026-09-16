# TTZZBX32 调试作弊模块（LSPosed）

面向 **com.openew.monster.ttzzbx32**（Cocos Creator 2.x / JSB 游戏）的 LSPosed 运行时 Hook 模块。
作用：在 **root + LSPosed** 环境下，无需改包、无需重签名、**无需键盘/蓝牙**，运行时激活游戏内置 `DebugCheat` 并提供一个**游戏内悬浮圆点 + 居中功能菜单**。

## 交互（完全替代键盘）

```
进入游戏 → 屏幕左上角出现白色圆点「功能」
   ↓ 点圆点
屏幕中央弹出「功能菜单」面板（两列网格，可点按钮直接作弊）
   ↓ 点「隐藏」
面板收起 → 变回左上角圆点（圆点仍在，可再次点开）
```

菜单功能项（点击即触发，等价官方 DebugCheat 按键）：

| 按钮 | 行为 |
|---|---|
| 一键胜利 | 置 presentBout/bossCnt → `trans(GAME_SUCCEED)` |
| 跳末波+英雄x100 | 清怪、补录精英击杀、法师 atk/hp ×100、生成最终 Boss |
| 收尾布置 | 最后一波 + Boss + 少量残怪（收尾测试） |
| 城墙无敌 开/关 | 直接翻转 `BattleTestManager.inst.isWallSuper` |
| 加速 / 减速 ±0.5 | `GameWorld.inst.gameSpeed`（0.5~4） |
| 战斗石+10 | `battle.battleStone += 10` |
| 掉落宝箱 | 随机掉一个增益宝箱 |
| 测试工具窗 | 打开官方测试工具窗口 |
| **隐藏** | 收起菜单，变回圆点 |

## 原理

游戏逻辑在 JS 层（`GameLogic.js`）。`DebugCheat` 被一道闸门拦死：

```
// GameLogic.js 109145
if ("debug" != Home.GlobalConfig.inst.channel) return [ 2 ];
```

模块 hook `Cocos2dxRenderer.onSurfaceCreated`（GL 线程、JS VM 已就绪），通过
`Cocos2dxJavascriptJavaBridge.evalString(String)`（签名 `(Ljava/lang/String;)I`，已从 dex 短描述符 `IL` 实证）注入一段幂等 JS：

1. 把 `Home.GlobalConfig.inst.channel` 的 getter 覆写为恒返回 `"debug"`（解锁全套 DebugCheat）；
2. 用 `cc.Node` + `cc.Graphics` 画圆点和居中菜单（`addPersistRootNode` 常驻，切场景不丢）；
3. 菜单按钮直接调 `window.DebugCheat.handleKeyDown(cc.macro.KEY.*)` 或改 `BattleTestManager.inst.isWallSuper`。

三层注入触发互为备份：`onSurfaceCreated`（主）→ 游戏自身 evalString 调用搭车 → Activity `runOnGLThread` 延迟重试（4s/10s/18s）。JS 侧另有 250ms×600 次自轮询，等 scene 与 GlobalConfig 就绪。

## 目录结构

```
ttzzbx32_hook/
├── app/src/main/
│   ├── AndroidManifest.xml            # xposedmodule / xposedminversion=93 / xposedscope
│   ├── assets/xposed_init             # com.openew.monster.ttzzbx32.hook.MainHook
│   ├── java/.../MainHook.java         # hook 链路 + 圆点/菜单 JS payload
│   └── res/values/{arrays,strings}.xml
├── build.gradle / settings.gradle / gradle.properties
├── .github/workflows/build.yml        # GitHub Actions CI（同 FhCheat 流程）
└── README.md
```

## 构建

### 方式 A：GitHub Actions（推荐）
把本目录推到 GitHub，push 到 `main`/`master` 或手动触发 workflow，Artifact `ttzzbx32-hook-module` 即模块 APK。

### 方式 B：本地
Android SDK（compileSdk 34、build-tools 34.0.0）+ JDK 17 + Gradle 8.9：

```bash
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 安装与激活

1. 手机已 root 并安装 **LSPosed**。
2. 安装模块 APK，在 LSPosed 中**勾选启用**。
3. 作用域勾选 `com.openew.monster.ttzzbx32`（模块已内置该 scope）。
4. **重启游戏**（杀进程重开）。
5. 进入游戏后左上角出现圆点即成功；Logcat 搜 `[TTZZBX32]` 可见注入日志。

## 注意事项

- 全部改动只在内存：APK 不动、卸载模块即还原、无悬浮窗权限要求（UI 画在游戏自己的 GL 画布内）。
- 仅**客户端权威**数值可改（战斗过程）；金币/钻石服务器权威；F1/F2/F3 注入类功能未放进菜单（有封号风险）。
- 若某按钮无反应，先看 Logcat：`fire()` 失败通常表示不在战斗场景（如 num2 只在战斗内有效）。
