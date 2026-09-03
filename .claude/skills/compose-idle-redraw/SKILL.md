---
name: compose-idle-redraw
description: >
  诊断 Kototoro 主壳"空闲永不定居"式 Compose 自持重绘循环（idle 帧数不为 0）。
  沉淀自 2026-09 历史页/收藏页 ~620 帧/5s 的整轮调试：gfxinfo 测量协议、快照写入
  归因（apply observer）、三大已实证根因模式（SnapshotStateMap put 通知语义 /
  data-class lambda 字段永不相等 / get-property 每读构造导致 effect 每帧 re-key）、
  探针工具箱与修复验证清单。
  Triggers: "空闲重绘", "idle frames", "帧数不归零", "一直重绘", "never settles",
  "gfxinfo frames rendered", "recompose loop", "自持循环", "空闲功耗", "recomposition 不停"
---

# Compose 自持重绘循环诊断（idle-redraw feedback loop）

> 实战锚点：2026-09，history/favourites 页空闲 5s 渲染 620-642 帧而 feed 为 0，
> 根因为两个独立反馈环，修复 commit `46047d6a7`（devel 分支）。归零后全功能回归通过。

## 1. 判定与第一反应

**症状**：页面静止、无输入、无动画，`dumpsys gfxinfo` 的 "Total frames rendered"
持续增长（120Hz 面板上 ≈ 60fps，即 5s 约 600 帧）。伴随现象：耗电、列表屏滚动发热。

**第一反应不是找"最热的符号"**。实测教训：`LazyGridState.applyMeasureResult` 占快照
写入 56%，但它只是"每帧重测"的**下游后果**，不是原因。链条是：
`某状态写入 → 祖先重组 → 布局阶段跑 → applyMeasureResult 写快照 → …`
追最热符号会带你绕一整圈回到起点。

**正确的第一刀是对照组**：
- 有问题的页 vs 恒为 0 的页（feed/订阅页），共用了哪些组件 → 差异即嫌疑面。
- 根层探针 `IdleProbeActivity`（已提交，`app/src/debug/.../core/dev/IdleProbeActivity.kt`，
  commit `4a124102e`）：KototoroTheme + 裸 LazyVerticalGrid + 240 静态项。
  它 0 帧 → 主题/窗口/insets/系统栏/Application 全部无罪，一次性剪掉大半搜索空间。

## 2. 测量协议（每次都按这个来，数字才可比）

```bash
P=org.skepsun.kototoro.debug
adb shell am force-stop $P
adb shell am start -n $P/org.skepsun.kototoro.main.ui.MainActivity
sleep 8                        # 冷启动稳定
adb shell input tap 342 2577   # 底部导航；Redmi K70 (1280x2772)：
                               # history=342, favourites=535, feed=923, 行 y=2577
sleep 6                        # 转场动画结束
adb shell dumpsys gfxinfo $P reset
sleep 5                        # 纯空闲窗口
adb shell dumpsys gfxinfo $P | grep -m1 'Total frames rendered'
```

规矩：**每测一次都 force-stop 重来**（进程内状态会互相污染）；先 reset 再读增量；
对照组页每轮都测（确认协议本身没坏）。修一处后**全部页重测**——history 修完后
favourites 还有 624 帧，因为它是第二个独立环，单根因假设会漏。

## 3. 归因阶梯（从粗到细，别跳级）

1. **快照 apply observer**（最有效的单步）：`Snapshot.registerApplyObserver` 能看到
   每个被 apply 的快照实际改变了哪些 state 对象，按对象 identity 计数。反复出现的
   对象 = 重组 driver。`registerGlobalWriteObserver` 看不到嵌套快照（重组协程内）
   的写入，别用它做第一步归因。
2. **put 点栈采样**：给疑似写入点加 1/40 采样栈打印（见工具箱），拿到完整调用链
   `Choreographer.doFrame → applyChanges → dispatchSideEffects/RememberObservers →
   路由代码 → map.put`，直接指认写者。
3. **identityHashCode 探针**区分"key 变了"vs"slot churn"：对 effect 的每个 key
   逐个打 identity。vm 稳定 + callback 稳定 + router 每次新 → 嫌疑立刻收敛到 router。
4. **布局阶段频率**（measure/ogp 每秒次数 vs compose 每秒次数）：measure ≈ 2×compose
   说明每帧双测（scrollable 内容区 + 外层），是重组的下游证据，不是独立根因。

## 4. 三大根因模式（已实证，先对照再创新）

### 模式 A：SnapshotStateMap 的 put 通知语义 + data-class lambda 字段
`MutableState.setValue` 在值相等时会去重；**`SnapshotStateMap.put` 不会**——值相等
也通知所有读者。而路由每次重组都重报状态，data-class 里的 lambda 字段
（`onSortOrderSelected`、`onClick`）每次构造都是新实例、用 identity 比较 →
`==` 永远不等 → shell 的相等门形同虚设 → 每次 put 都通知 → shell 重组 → 路由再报。
**环闭合条件：写 map 的人（或其祖先）自己也读这个 map。**

### 模式 B：get-property 每读构造 → DisposableEffect 每帧 re-key
```kotlin
inline val FragmentActivity.router: AppRouter get() = AppRouter(this)  // 每读一个新实例！
```
把它直接写进组合，`DisposableEffect(appRouter, vm)` 的 key 每帧都变：
每帧 forget（`onDispose` → 报空 → map.remove）+ remember（`onRemembered` → 报有 →
map.put）。**这种"在场/缺席交替"是真变化，任何相等门都救不了**——必须让 key 稳定
（`remember(activity) { activity.router }`）。无状态 facade 对象都该这么包。

### 模式 C：反馈环 = 写者的祖先读它写的东西
通用判定：列出"谁写 X、谁读 X"。shell 把路由报告存进 snapshot map，又在自己
scope 里读这个 map 渲染顶栏 → 自持。哪怕写入值不变，map 的通知语义也够点火。

## 5. 探针工具箱（粘贴即用，用完删净）

### 计数器（树宽 compose/measure 频率）
```kotlin
object DevCount {  // 放 app/src/main/.../core/dev/ 或 debug sourceSet
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val started = AtomicBoolean(false)
    fun startDumpThread() { if (started.compareAndSet(false, true)) Thread {
        while (true) { Thread.sleep(4000)
            Log.i("KototoroLayoutProbe", "COUNTS " + counts.entries.joinToString(" ") { (k,v) -> "$k=${v.get()}" })
        }
    }.apply { isDaemon = true; name = "devcount" }.start() }
    fun inc(key: String) { startDumpThread(); counts.computeIfAbsent(key){AtomicLong()}.incrementAndGet() }
}
// 用法：composable 体首行 DevCount.inc("favRoute.compose")；Modifier.layout 里 inc("grid.measure")
```

### 热点栈采样（1/40）
```kotlin
fun incWithStack(key: String, tail: Int = 22) {
    inc(key); val n = counts[key]!!.get()
    if (n % 40L == 0L) Log.i(TAG, "STACK $key :: " + Thread.currentThread().stackTrace
        .drop(2).take(tail).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" })
}
```
看栈里有没有 `dispatchSideEffects`（SideEffect 报的）/ `DisposableEffectImpl.onRemembered`
（effect 被 re-remember，key 在变！）/ `applyMeasureResult`（布局下游，别追）。

### apply observer（重组 driver 归因）
```kotlin
Snapshot.registerApplyObserver { changed, _ ->
    for (state in changed) {
        val k = state::class.java.name + "@" + Integer.toHexString(System.identityHashCode(state))
        appliedCounts.computeIfAbsent(k){AtomicLong()}.incrementAndGet()
        trackedStates.putIfAbsent(k, state)  // dump 时读 .value 看 VALUE= 是谁
    }
}
```
输出形如 `A#1 x482 ParcelableSnapshotMutableState VALUE=SomeLambda@新地址每帧变`。

### ⚠️ 观察者悖论（大坑）
**在首次组合前注册 global write observer 会直接杀死这个环**（0 帧 vs 626 帧）——
它改变了全局快照写入的 apply 时机。所以要用 marker 文件轮询（500ms 查
`/data/local/tmp/kototoro_layout_trace` 存在才注册），把观察者**后挂到已运行的环上**。
如果挂上后环死了，本身也是一条诊断信息（环依赖全局快照 apply 时序）。

## 6. 修复模式

- **语义等价函数**（模式 A 的解）：手写 `overrideStateEquivalent(a, b)` 式比较——
  逐类型比较**语义字段**（选中数、tab 列表、排序、action 的 title/icon），
  **刻意忽略回调 identity**。安全性论证：存的回调一直引用它捕获的 vm/状态；
  语义字段一变，门放行新实例（带新回调），接线不会过期。
- **remember 包 facade**（模式 B 的解）：无状态、每读构造的对象一律
  `remember(owner) { ... }`。
- 不要顺手"优化"报告侧（把 SideEffect 换 LaunchedEffect 之类）——报告侧 churn
  本身无害，只有写入门 + 祖同读者才成环。动最小面。

## 7. 修完必做（否则会回归到用户手里）

1. 全部页 idle 重测归零（含恒 0 对照页，防协议漂移）。
2. **逐条门控路径功能回归**：选择模式计数增减/清除、溢出菜单逐项渲染、
   分类 tab 切换后列表内容变化。门最怕"该放行的没放行"。
   注意 uiautomator dump 对 Compose 下拉菜单**不可靠**（内容已渲染但 dump 出旧屏），
   用截图确认或直接在数据消费点打点。
3. logcat 查 `FATAL EXCEPTION`（门路径空指针类崩溃）。
4. `./gradlew :app:testDebugUnitTest --no-daemon` 过一遍。
5. **剥净全部探针再提交**：`git checkout --` 恢复纯探针文件、删未跟踪探针、
   手工剔除 fix 文件里的 DevCount 行，commit 只含修复（diff 里 grep DevCount 应为空）。

## 8. Kototoro 已排除清单（勿重复劳动）

以下已逐一实测或反证排除：系统动画缩放；玻璃/导轨/共享转场、卡片 sharedBounds；
AnimatedFaviconDrawable / AnimatedPlaceholderDrawable；RetainedPagingSnapshotController
（grep 证明根本未组合，"LazyPagingItems 差异"推论作废）；withMacroOptionsFirst
（已修缓存但帧数不变）；LayerBackdropModifier 几何门控（改了更差，已回退）；
主题/窗口/insets/系统栏/Application（IdleProbeActivity 0 帧）；外壳/导航/列表屏
（feed 用同一 KototoroContentListScreen 为 0 帧）。

其他坑：sed 按行号改码极易改出空实验，必须回显补丁内容确认；edit 工具遇
"file changed since read" 就重读再改；先实测再改码——每一条推论都要有
对应的帧数/计数/栈作为证据链。
