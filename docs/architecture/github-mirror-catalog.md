# GitHub 镜像目录与实时同步

## 动机

GitHub 下载镜像（`kkgithub`、`ghproxy` 等）过去是编译期枚举：新增/失效一个镜像要发版。
更糟的是"鸡生蛋"问题——当 GitHub 被墙时，应用恰恰无法从 GitHub 拉取"修复 GitHub 访问"的镜像列表。

本方案把镜像列表变成**可远程刷新的清单**，且清单本身从**非 GitHub 地址**（jsDelivr CDN）拉取：
拉取镜像列表这一步永远不依赖 GitHub 可达性。

## 架构

```
docs/github-mirrors.json （仓库内，维护者更新）
        │
        ▼  候选 URL 链（依次尝试，任一个成功即停）:
        │   ① https://cdn.jsdmirror.com/gh/<repo>@main/docs/github-mirrors.json
        │   ② https://cdn.jsdelivr.net/gh/<repo>@main/docs/github-mirrors.json
        │   ③ https://fastly.jsdelivr.net/gh/<repo>@main/docs/github-mirrors.json
        │   ④ https://gcore.jsdelivr.net/gh/<repo>@main/docs/github-mirrors.json
        │   ⑤ https://raw.githubusercontent.com/<repo>/main/docs/github-mirrors.json （最后兜底）
        ▼
GitHubMirrorCatalogRepository （@Singleton）
        │  fetch（@BaseHttpClient 直连，不经任何镜像，10s 超时，用户可取消）
        ▼
校验 + normalizeMirrors + SharedPreferences 持久化
        │
        ▼
entries: StateFlow<List<GitHubMirrorEntry>>  ──► 设置页 / 首次启动向导 / 应用更新页
```

> **状态**：同步与镜像连通性测试都是**用户手动触发**，且**全程可取消**；刷新失败时安静回退到上次列表
> （`Failed` 仅做一次摘要显示，`Idle` 保持上次列表）。CDN 404 是最常见的"失败"原因——通常是清单没有推送到
> `main` 分支，候选链会逐个换源重试。

### 关键类型（`core/prefs/GitHubMirrorCatalog.kt`）

| 类型 | 说明 |
|---|---|
| `GitHubMirrorEntry` | 一个镜像：`id`（持久化偏好值）、`name`、`strategy`、`host`/`rawHost` |
| `GitHubMirrorStrategy` | `NATIVE` / `HOST_REPLACE` / `PREFIX` / `JSDELIVR` 四种 URL 改写策略 |
| `GitHubMirrorManifest` | 远程清单：`version` + `updatedAt` + `mirrors[]` |

URL 改写按 `strategy` 分派（不再对枚举做 exhaustive `when`），因此远端新增镜像**无需改代码即可生效**：
`applyGitHubMirror(url, entry)`（`extensions/repo/GitHubMirrorUrl.kt`）。

### 选择存储

选择仍存于 `github_mirror` 偏好键，但值是**自由字符串 id**（`AppSettings.gitHubMirrorId`），
远端清单引入的 id 也能保存。旧值（`native`/`kkgithub`/…）与内置 id 完全一致，**无需迁移**。
`AppSettings.gitHubMirror` 枚举视图保留为兼容别名（未知 id 回退 `NATIVE`）。

### 合并规则（`normalizeMirrors`）

1. `native` 永远排第一且永远存在（用户随时能改回直连）；
2. 清单条目按原顺序紧随其后，重复 id 以先出现者为准；
3. 清单中缺失的内置镜像追加在尾部——即使清单过期，已知镜像也不会凭空消失。

### 防护

- 清单解析失败 / `version` 为空 / 超过 64 个条目 / id 不匹配 `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` → 拒绝保存，保留上次列表；
- 拉取用 `@BaseHttpClient` 裸客户端直连，10 秒超时，**绝不经过所选 GitHub 镜像**；
- 用户自定义镜像列表地址时，**只使用该地址**（尊重显式选择）；未填才走上面 5 条候选链；
- 新列表保存成功后，旧的探测延迟结果一并清空（列表已变，延迟无意义）。

### 连通性测试（探测）

`probeMirrors()` 对列表里每个镜像**并发**拉取一个始终存在的 GitHub 小文件
（`raw.githubusercontent.com/skepsun/kototoro-parsers/repo/index.min.json`），
只测"能不能通 + 多少延迟"，不动用户当前选择。结果：

- `GitHubMirrorProbeState.Running(completed, total)` — 逐项推进；
- `GitHubMirrorProbeState.Finished(available, total, fastestId, fastestMillis)` — 汇总最快镜像；
- `probeResults: Map<id, GitHubMirrorProbeResult>` — 逐项延迟/超时，UI 在镜像标签旁追加 ` · 213 ms` / ` · 超时`；
- 每镜像 6 秒超时，用户可"取消测试"；取消时保留已完成的部分结果，整体回到 `Idle`。

### 向导配置阶段：逐仓库进度 + 可取消

首次启动向导在“仓库”页确认配置后（`WelcomeViewModel.initializePlugins`）：

- 每个仓库（`WizardRepoFetchStatus`：`PENDING`/`RUNNING`/`DONE`/`FAILED`）有一行状态：
  本地类（Legado/TVBox/LNReader 直接写 URL）瞬间 `DONE`；网络类（Mihon/Aniyomi/IReader/Cloudstream 等）
  在 `prepareAddRepo`/`confirmAddRepo` 期间 `RUNNING`，通过即 `DONE`，失败即 `FAILED`（显示原因）。
- 单仓失败**不再中断整个配置**：原本一个源失败会中止全部并回到配置页；现在只把该仓标红，其余继续。
  若最终没有任何可配置源，才有统一的“未配置任何源”提示。
- 循环在仓库之间检查 `ensureActive()`，页面上有“取消”按钮 → `cancelWizardConfiguration()` 立刻停，
  状态回到 `CONFIGURATION` 并提示“已取消仓库配置”，不弹“严重错误”toast。

## 维护者：如何更新镜像列表

1. 编辑 `docs/github-mirrors.json`（增删条目、递增 `version`、更新 `updatedAt`）；
2. 提交并推送到 `main`；
3. 用户端"刷新镜像列表"即拉到新清单。

> **jsDelivr 缓存提示**：`@main` 引用有最长 12 小时的 CDN 缓存。若需要即时生效，
> 可让用户在"镜像列表地址"里填带精确 tag 的地址（如 `…/gh/Kototoro-app/Kototoro@v1.9.9/docs/github-mirrors.json`），
> 或直接指向自建静态端点。该地址可在 设置 → 存储与网络 → 代理镜像 中覆盖，留空则用默认 jsDelivr 地址。

### 清单 schema

```json
{
  "version": "1.0.0",
  "updatedAt": "2026-08-31",
  "mirrors": [
    { "id": "native", "name": "Direct Native (Default)", "strategy": "NATIVE" },
    { "id": "kkgithub", "name": "KKGithub Proxy", "strategy": "HOST_REPLACE",
      "host": "kkgithub.com", "rawHost": "raw.kkgithub.com" },
    { "id": "ghproxy", "name": "Ghproxy.com", "strategy": "PREFIX", "host": "mirror.ghproxy.com" },
    { "id": "jsdmirror", "name": "jsDelivr (jsdmirror)", "strategy": "JSDELIVR", "host": "cdn.jsdmirror.com" }
  ]
}
```

- `strategy` 含义：`NATIVE` 不改写；`HOST_REPLACE` 替换域名（github.com→host、raw.githubusercontent.com→rawHost）；
  `PREFIX` 前缀代理；`JSDELIVR` 重写为 `<host>/gh/<owner>/<repo>@<ref>/<path>`（仅 raw 文件类 URL，release 资产不动）。
- 未知字段会被忽略（向后兼容）。
- 内置 id 的显示标签仍走 `pref_github_mirror_entries` 数组（本地化）；远端新增条目显示 JSON `name`。

## UI 入口

| 位置 | 能力 |
|---|---|
| 设置 → 存储与网络 → 代理镜像 | 选择镜像（动态列表，标签带探测延迟）、**刷新/取消同步**（含版本/时间摘要）、**测试/取消连通性**、镜像列表地址覆盖 |
| 首次启动向导 → 仓库页 | 镜像下拉（动态，菜单项带延迟）+ 行内刷新/测试（同步中/已同步 vN/失败保留上次）；配置期间逐仓库进度 + 取消 |
| 应用更新页（GitHub 源） | 镜像芯片（动态列表） |

同步为用户手动触发，不做后台定时轮询（避免无提示的对外网络请求）。
