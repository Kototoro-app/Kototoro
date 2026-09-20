# Scene Reader 2.5× 超时帧分析（2026-09-20）

关联：[改进与验收计划](reader-scene-improvement-plan-2026-09.md) §4.3、§10.1 交付 9。

## 结论

5 份归档 trace 共复现 **3,163 个 Macrobenchmark 样本，41 帧 overrun > 0（1.296%）**，
各轮最大连续超时均为 **2 帧**，真正的最大 overrun 为 **752.860ms**。
历史 **+13.1ms 是五轮合并样本的 P99**，既不是最大单帧，也不是各轮 P99 的中位数。

每轮均有 3 次主线程长帧，15 次 `doFrame` 耗时 **127.198–764.516ms**。
最差帧主要耗在主线程 `animation` 阶段的睡眠等待，实际运行时间很短；
现有证据不能继续支持“CPU P99 很低，所以长尾来自 GPU／纹理上传”的推断。

**首要代码候选是主线程关闭 region decoder 时等待正在解码的读锁**。
trace 已证实主线程长等待及后台 decoder 锁竞争，代码路径也吻合；但没有等待调用栈或
`closeSession` 专用 trace section，尚未直接证明主线程等待的就是该锁。
建议先做这个候选的定点验证，再决定阶段 D retained layer PoC 的优先级。
高倍率场景仍为 **待验收**，本轮不制定或放宽 SLO。

## 样本与工具

- 被测代码：`7db11a1d5`，上一轮 benchmark APK SHA 前缀 `332d461f`；本轮只分析原始产物。
- 原文档基线：`3ca484e5fe8f98623d8ea4fe976cace38c3d6d57`。
- 设备与旅程沿用交付 9：Redmi M332BF、Android 17、1280×2772、60Hz，
  `CompilationMode.Full`，`pagedLargeZoomedSceneFull`，6000×9000、打开即 2.5×，40 次 swipe。
- AndroidX Macrobenchmark **1.5.0**；Perfetto **v58.2-add693d8b**，RPC API 14。
- 工具由官方 `https://get.perfetto.dev/trace_processor` 获取，launcher 校验预编译文件 SHA。
  用户级入口：`~/.local/bin/trace_processor`；实际文件位于 `~/.local/share/perfetto/`。
  不涉及项目依赖升级。
- 原始 trace 目录：`/tmp/reader-bench/scenarios/zoomed2_5-traces/`。
- 同次 benchmark 的 JSON 已从 `macrobenchmark/build/outputs/connected_android_test_additional_output/`
  复制到 `/tmp/reader-bench/zoom-analysis/benchmarkData.json`，SHA-256：
  `2bb8b1c6b2f22d7cbb7d6da981bb4a4593812e701fde589847f1d1833f88f459`。
- 逐帧 CSV、统计、超时帧探针、trace 哈希输出到 `/tmp/reader-bench/zoom-analysis/verified/`。
  这些是本机产物，`/tmp` 不保证永久保留；仓库保存复现脚本和本报告，不保存大体积 trace。

## 取帧与统计口径

脚本 [analyze_reader_frames.py](../../scripts/analyze_reader_frames.py) 使用标准库调用 Perfetto SQL。
根据 AndroidX `FrameTimingQuery`，并核对本机 **1.5.0 AAR 字节码**：

1. 限定精确进程 `org.skepsun.kototoro`；UI 取主线程 `Choreographer#doFrame`，
   RT 取 `RenderThread` 的 `DrawFrame*`；过滤未结束和 `resynced` slice。
2. UI/RT 按 frame ID 匹配；actual 从时间池反向搜索，起点早于 UI 起点 + 50µs，
   且包含 UI 中点；每个 actual 仅消费一次。expected 按 actual frame ID 匹配。
   不能简单用四张表的相同 ID 做 join，否则不等价于 benchmark 的 resync 处理。
3. `CPU = RT.end − UI.start`，这是墙钟跨度，包含等待，**不是实际 CPU running 时间**。
4. `overrun = max(actual.end, RT.end) − expected.end`，保留 AndroidX 的 RT-end 修正。
   本批 expected 的应用预算约 **13.667ms**；60Hz 的 16.667ms 周期不能替代应用 deadline。
5. 仅 `overrun > 0` 算超时；与 FrameTimeline 的 `jank_type`、`Late Present` 分开统计。
6. 连续超时：每轮按 UI 起点排序，在匹配到的完整帧序列中计数；非超时帧中断，迭代间不连接。
   它不是错过的显示刷新次数，也不包含没有完整样本的帧。
7. P50/P95/P99 使用 `(N−1)×p` 线性插值；总表合并各轮样本，比例用总超时数／总帧数。
8. 逐轮帧数及 **全部 6,326 个 CPU/overrun 数值**与原 JSON 一一核对排序后的样本集合，
   再核对合并 P50/P95/P99；不一致或缺 trace 时直接报错，不输出通过结论。

与历史 benchmark 一致，主表使用整个 capture。按 `measureBlock` 的 UI 起点范围过滤后为
3,160 帧／41 超时（1.297%）：iter 0 尾部少 1 个非超时帧，iter 2 尾部少 2 个非超时帧。
**所有超时帧都在 measureBlock 内**，长尾不是 setup 或 capture 尾部引入的。

## 五轮结果

单位 ms；行号对应 trace 文件的 `iter000`–`iter004`。

| 迭代 | 帧数 | 超时数 | 超时比例 | 最大连续 | overrun P50 | P95 | P99 | 最大 overrun |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 621 | 8 | 1.288% | 2 | -10.403 | -7.843 | +10.915 | +735.925 |
| 1 | 635 | 10 | 1.575% | 2 | -10.544 | -8.135 | +17.647 | +752.860 |
| 2 | 647 | 8 | 1.236% | 2 | -10.617 | -7.970 | +5.824 | +746.685 |
| 3 | 618 | 7 | 1.133% | 2 | -10.439 | -7.946 | +8.546 | +744.910 |
| 4 | 642 | 8 | 1.246% | 2 | -10.607 | -7.973 | +9.096 | +723.603 |
| 合并 | 3,163 | 41 | **1.296%** | **2** | -10.535 | -7.961 | **+13.060** | **+752.860** |

| 迭代 | CPU P50 | CPU P95 | CPU P99 |
| --- | ---: | ---: | ---: |
| 0 | 2.232 | 4.632 | 6.862 |
| 1 | 2.056 | 4.417 | 7.366 |
| 2 | 2.027 | 4.510 | 7.883 |
| 3 | 2.174 | 4.543 | 6.914 |
| 4 | 1.987 | 4.445 | 7.293 |
| 合并 | **2.088** | **4.511** | **7.664** |

15 个 UI 长帧占总样本 **0.474%**，所以 CPU P99 可以很低，同时仍反复出现约 0.75 秒长帧。
30 个 overrun > 100ms 样本中，每轮 3 个是长 UI 帧，另 3 个是紧随其后的 resync 帧；
后者 UI 工作已经很短，但 expected deadline 仍旧过期。不能把成对 overrun 相加当作卡顿时长。
同理，最大连续 2 帧不能被解释为“只停顿 2×16.7ms”。

41 个超时帧的 jank 标签：`App Deadline Missed` 20，
`App Deadline Missed, App Resynced Jitter` 15，
`SurfaceFlinger Scheduling, App Resynced Jitter` 4，`SurfaceFlinger Scheduling` 1，`None` 1。
最后一帧 overrun 为 +0.354ms，但 FrameTimeline 判为 On-time Present；两种口径不能互相替代。

## 最差帧证据与候选归因

所有轮次最差帧都在 measureBlock 开始后约 **1.35–1.38 秒**出现。
下面 running / sleeping / runnable 均为 `thread_state` 与 UI slice 相交后的时长；
`R` 和 `R+` 合并为 runnable。不要把父子 slice 时长相加。

| 迭代 | frame ID | UI 墙钟 | UI running | UI sleeping | UI runnable | RT 墙钟 | animation |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 9628506 | 747.551 | 14.464 | 732.957 | 0.130 | 7.548 | 734.108 |
| 1 | 9647299 | 764.516 | 11.485 | 752.983 | 0.048 | 9.199 | 751.652 |
| 2 | 9665994 | 758.299 | 13.491 | 744.616 | 0.192 | 8.860 | 744.687 |
| 3 | 9684524 | 756.457 | 14.384 | 742.005 | 0.068 | 7.724 | 744.855 |
| 4 | 9703277 | 735.156 | 10.607 | 724.358 | 0.191 | 9.007 | 721.068 |

全局最差：`iter001_2026-09-20-08-05-36`，frame **9647299**。

- UI slice ID **59795**，RT slice ID **62413**，actual **59796**，expected **59767**。
- UI 起止 ns：`86705637188054` → `86706401703835`。
- expected deadline ns：`86705650695009`；actual end ns：`86706403554928`。
- 最长单段睡眠 **744.772ms**；`postAndWait` **8.195ms**；`Record View#draw()` **0.587ms**。

假设核验：

| 候选 | 预测与观察 | 本轮结论 |
| --- | --- | --- |
| 主线程同步等待资源锁 | 主线程长 S、很少 running；同时后台 decoder 竞争 | **最吻合**；具体等待锁尚无直接栈证据 |
| GC 或调度饥饿主导 | 应有相应长 STW 或长 runnable | 最差帧 runnable 仅 0.048–0.192ms；iter 0 重叠 GC 的 suspend 事件约 0.01ms，不能解释 726ms 单段睡眠；仍不宣称穷尽所有运行时原因 |
| RT 上传／GPU 等待主导 | 长时间应落在 RT 或主线程 postAndWait | 最差帧 RT 7.5–9.2ms、postAndWait 6.4–8.2ms；15 个 UI 长帧的对应 RT 内均无 `Texture upload*` slice。该假设不能解释这批数百毫秒长尾；不排除其他帧的上传成本 |

后台 `monitor contention*BitmapRegionDecoder*` 每轮 **326／342／329／331／361** 个，
最长分别 **1349／1307／1393／1464／1391ms**。第一轮可见 `waiters=62`。
这是工作线程等待平台 decoder monitor 的证据，不直接等于主线程等待同一 monitor。

高度吻合的代码链（相关实现相对被测 commit 无变更）：

1. `ComposeScenePagedReader` 用 `rememberCoroutineScope()` 创建 adapter，scope 传入 `ReaderTileManager`。
2. `ReaderTileManager.releasePage()` 取消 tile jobs，再调用 `closeSession()`。
3. `closeSession()` 用无 dispatcher 覆盖的 `scope.launch` 调用 `session.close()`；
   session 已完成时 `await()` 可直接返回，因此关闭会在宿主主线程执行。
4. `AndroidTileDecodeSession.close()` 同步获取 `ReentrantReadWriteLock(true)` 写锁。
5. 每个 `decodeRegion()` 在 IO 上持读锁进入平台 `BitmapRegionDecoder.decodeRegion()`；
   多个已入场读者可能排队等待平台 monitor。取消 job 不会中断已运行的同步 native 解码／锁等待。

这条路径能够造成主线程等待已入场的 decoder 读者排空；但当前 trace 无 Java/native 调用栈，
也没有 `closeSession`／读写锁等待专用 section。**必须通过定点 trace 或单变量 A/B 才能确认因果。**
同样，历史 RSS Max→Last 回落只能证明资源回落；`TileEvictions=0` 时不能单凭 RSS
宣布预算驱逐工作正常，`releasePage` 等释放路径也可能解释回落。

## 证据限制与下一步

- 只有打开即 2.5× 的 5 份 trace；不能把具体调用链结论直接外推到 2.0×。
- 各 trace 有 6 个 `ftrace_setup_errors`（notice）和 1 个 `power_rail_empty_packet`（error）。
  iter 1、4 另有 `traced_buf_sequence_packet_loss=1`。完整样本与原 JSON 一致只能验证提取，
  不能证明底层采集无丢失；iter 0、2、3 无该 packet-loss 标记，且复现相同长等待。
- 五轮 `cpu_profile_stack_sample` 均为 0；首轮长睡眠没有 `waker_utid`／`blocked_function`。
  不具备用调用栈直接定位锁拥有者的条件。
- retained layer 可能改善其他绘制成本；当前 0.5–0.8ms 的最差帧录制成本无法解释 700ms 等待。
- 后续优先给 `closeSession`、写锁获取和 decode 生命周期加窄范围 trace，或以确保后台关闭、
  生命周期正确为单变量实验；保留本次 APK／配置作对照，交错 A/B 并逐轮归档。
  需验证释放完成、取消、异常和宿主销毁后无 decoder 泄漏；不能仅把任务挂到一个即将取消的 scope。
- 复测 1.5×／2.0×／2.5×，同时记录最大 UI 阻塞、最大 overrun／长帧时长，
  再固定高倍率 SLO。仅 P99、超时比例与连续帧数会掩盖当前稀疏长卡顿。

## 后续：关闭路径假设已通过单变量 A/B 验证（同日）

修复（后续 commit）：`ReaderTileManager.closeSession()` 的关闭协程改为在
`decodeDispatcher` 上执行并以 `NonCancellable` 包裹关闭体；同步在
`closeSession` 与 `AndroidTileDecodeSession.close()` 写锁处添加
`Reader.TileSessionClose`／`Reader.SessionCloseWriteLock` 窄 trace section。
JVM 测试先行（先红后绿）：`releasePage` 的 session close 必须发生在
decode dispatcher 线程而非调用方线程。

A/B（各侧独立 APK、安装核对、同场景同旅程、Full×5 迭代、交错两轮 +
2.0× 与普通页单页对照各一轮，本机产物 `/tmp/reader-bench/fix-ab/`）：

| 指标（zoomed 2.5×，脚本核实合并值） | A 基线 `332d461f` | B 修复 `a9221207` |
| --- | ---: | ---: |
| 最大单帧 overrun | +729.254ms（各轮 710–729ms） | **+50.518ms**（-93%） |
| overrun P99 | +11.577ms | +3.116ms |
| 超时帧比例 | 1.257% | 1.628% |
| 总帧数 | 3,183 | 3,931（+23%） |
| 普通单页对照 overrun P99 | -5.1ms | -5.0ms（无回归） |

判读：700ms 级主线程长帧消失，因果成立。超时比例小幅上升（1.26%→1.63%）
伴随帧数 +23%：原被长停顿吞掉的 vsync 回来了，剩余超时帧是 tile 到达的
普通尾延迟（P99 +3.1ms、最大 50ms），不再由关闭路径主导。高倍率组仍
**待验收**：残差尾延迟与 SLO 制定留待后续轮次（可考虑 tile 到达调度）。

## 复现与验证

在仓库根目录执行（工具入口不在 PATH 时传 `--trace-processor` 绝对路径）：

```bash
python3 "scripts/analyze_reader_frames.py" \
  --trace-processor "/Users/sunchuxiong/.local/bin/trace_processor" \
  --trace-dir "/tmp/reader-bench/scenarios/zoomed2_5-traces" \
  --benchmark-json "/tmp/reader-bench/zoom-analysis/benchmarkData.json" \
  --benchmark-name pagedLargeZoomedSceneFull \
  --output-dir "/tmp/reader-bench/zoom-analysis/verified"
```

已执行输出：`Verified all original samples; pooled 41/3163 (1.296%), P99 13.060 ms`。
另外以合成输入验证了严格正值边界、连续段中断、actual/UI ID 不同的匹配、actual 单次消费、
RT-end 修正、缺帧及原始样本不一致时拒绝结果。脚本不自动宣布 SLO 通过。
本轮修改为诊断脚本与文档，未改变 Android 代码，无需重新构建 APK。

需要复查后台竞争时，对任一原始 trace 运行以下 SQL：

```sql
SELECT s.id, s.ts, s.dur / 1e6 AS ms, t.name AS thread, s.name
FROM slice s
JOIN thread_track tr ON s.track_id = tr.id
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.name = 'org.skepsun.kototoro'
  AND s.name GLOB 'monitor contention*BitmapRegionDecoder*'
ORDER BY s.dur DESC;
```

原始 trace SHA-256（公共文件名前缀 `ReaderProductionBenchmark_pagedLargeZoomedSceneFull_`）：

| 文件后缀 | SHA-256 |
| --- | --- |
| `iter000_2026-09-20-08-05-12.perfetto-trace` | `db8b8c75b3fce0b1022d309a1dbec4cf4ed07c235deb049a67a2ef4f93fba275` |
| `iter001_2026-09-20-08-05-36.perfetto-trace` | `44dd75b0a32925f67d0172100cad18e946afed5f5bcf7caadb4f120892f0d8b4` |
| `iter002_2026-09-20-08-05-59.perfetto-trace` | `4968235abdaaae5b4e98bfb84c00a8a74d2e686f94e6f7ecc601b1eb3b291554` |
| `iter003_2026-09-20-08-06-22.perfetto-trace` | `acc598ba5ed5f91505fdc8538c6323417fdfc035fc8872234e8a11bd9ae76d4b` |
| `iter004_2026-09-20-08-06-45.perfetto-trace` | `5698415a4014a3a9e707ac8132a2fa13c5893bbea95f16b49338488673914998` |

官方依据：

- [Perfetto FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline)：expected/actual 与 jank 语义。
- [AndroidX FrameTimingQuery](https://github.com/androidx/androidx/blob/androidx-main/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/perfetto/FrameTimingQuery.kt)：取帧和指标公式；本轮另核对本地 1.5.0 字节码。
- [Macrobenchmark 指标](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)：帧耗时和 overrun 的用途。
