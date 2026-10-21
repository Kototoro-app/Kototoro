# MangaBaka 追踪接口可行性核查

核查日期：2026-10-21。对应 issue #260。本文仅记录官方契约、官方网页资源和无凭据 GET 探测；没有注册应用、创建令牌、登录或写入个人书库。

## 结论

MangaBaka 已具备实现完整追踪服务所需的官方接口：搜索、用户资料、个人书库读取、添加、修改阅读状态/章节/卷/评分和删除。不能再把它仅视为元数据来源。建议优先采用 **Native/Public OAuth + PKCE**，由维护者注册 Kototoro 应用并配置回调；用户手动输入 PAT 可作为备选入口。OAuth 完整往返、PAT 有效性及授权后的写入均未实测，不能宣称已经验证集成可用。[OpenAPI](https://mangabaka.org/api.json)、[官方应用注册 UI 资源](https://mangabaka.org/_app/immutable/nodes/38.DtDfU9vi.js)

## 证据与获取方式

- [API 总览](https://mangabaka.org/data/api)和[Explorer](https://mangabaka.org/data/api/explorer)是入口。Explorer 当前加载的[官方脚本](https://mangabaka.org/_app/immutable/nodes/86.CrgpN4V8.js)配置 `url: /api.json`，真实 OpenAPI 是 **`https://mangabaka.org/api.json`**，API 服务根地址是 `https://api.mangabaka.org`。`api.mangabaka.org/openapi.json` 返回 404，不应据此判断没有规范。
- 使用 `curl -L --max-time 45 -s https://mangabaka.org/api.json` 成功获取 JSON；本次 Python `urllib` 请求同站页面遭遇 403，网页读取工具也未能打开 JSON。获取差异不代表业务 API 要求登录。
- 临时规范位于 `/tmp/kototoro-mangabaka-api.json`，未加入仓库；本次内容 SHA-256：`88e9f6b35b67983df8b1980e7ec5cc9ba9dffccd60e04773f3a64314464d6ae8`。动态站点资源文件名和规范内容会变，应从 Explorer 重新发现。
- 官网 API 总览仍提示 schema 可能随时变化；OpenAPI 自身却将 v1 标为 stable、v2 标为 beta，两处稳定性表述并不完全一致。近期[官方变更记录](https://mangabaka.org/data/api/changelog)也包含破坏性修改，应按具体字段防御性解析。

## 授权

### OAuth：原生公共客户端有官方支持证据

[OIDC discovery](https://mangabaka.org/.well-known/openid-configuration)声明：

| 项目 | 当前值 |
| --- | --- |
| issuer | `https://mangabaka.org/auth` |
| authorization endpoint | `https://mangabaka.org/auth/oauth2/authorize` |
| token endpoint | `https://mangabaka.org/auth/oauth2/token` |
| 撤销端点 | `https://mangabaka.org/auth/oauth2/revoke` |
| 授权类型 | `authorization_code`、`client_credentials`、`refresh_token` |
| PKCE | `S256` |
| 所需权限 | `library.read`、`library.write`；长期访问还需 `offline_access` |

discovery 的 `token_endpoint_auth_methods_supported` 仅列 `client_secret_basic` 和 `client_secret_post`，没有 `none`。但官方[新建应用 UI](https://mangabaka.org/_app/immutable/nodes/38.DtDfU9vi.js)明确支持 `Native App`、`Public application (OAuth without secret)`，并说明原生应用和 SPA 应使用 PKCE。配套[表单校验资源](https://mangabaka.org/_app/immutable/chunks/DlneE-iy.js)要求 native/other 必须 public，提供 `authorization_code`、`refresh_token`，说明非 localhost 的 HTTP 回调不允许，回调应精确匹配。由此可以确认官方设计支持原生公共客户端；discovery 中缺少 `none` 是需要联调核对的不一致，不能反推必须引入后端或在 APK 中嵌入 secret。

注册入口为 [`/my/apps/new`](https://mangabaka.org/my/apps/new)，账号设置中的 OAuth applications 可跳转该入口。维护者仍需实际完成注册，确认 Android 自定义 scheme/App Link 回调能通过服务端校验，并验证 code + verifier 换取 token、刷新、撤销和取消授权；本次没有进行这些操作。[官方账号设置 UI](https://mangabaka.org/_app/immutable/nodes/59.FCXGOxsr.js)

### PAT：可作为手动接入备选

官方账号设置入口为 [`/my/settings/api-and-apps`](https://mangabaka.org/my/settings/api-and-apps)。其[公开前端代码](https://mangabaka.org/_app/immutable/nodes/59.FCXGOxsr.js)提供 `New token`：用户输入名称后调用自身账号的 `apiKey.create`，前缀为 `mb-`，当前 UI 达到 5 个 token 时禁止再创建；还展示过期时间和删除按钮。该 UI 明确说明 PAT 拥有账号完整权限，未提供细分 scope 选择。这里证明存在用户自建流程，不代表本次已创建或验证任何 token。

OpenAPI 将 PAT 定义为请求头 `x-api-key: mb-...`，书库读写端点均接受 PAT；OAuth 则使用 `Authorization: Bearer ...`。因此手动 PAT 路径在契约上可行，无需 Kototoro 注册 OAuth client，但仍需有效用户令牌做受控联调。首版若提供该入口，应明确它是用户完整账号权限凭据。[OpenAPI securitySchemes 与 `/v1/my/*`](https://mangabaka.org/api.json)

## 追踪能力与接口语义

以下均来自[官方 OpenAPI](https://mangabaka.org/api.json)，属于契约证明，未经授权请求验证。

| 能力 | 接口 | 关键语义 |
| --- | --- | --- |
| 搜索与详情 | `GET /v1/series/search`、`GET /v1/series/{id}` | 无需授权；也有 v2 返回模型 |
| 验证账号/权限 | `GET /v1/my/profile` | PAT 或任意 OAuth token；返回用户 ID、`auth_type`、`scopes`、`rating_steps` |
| 全量书库 | `GET /v1/my/library` | OAuth 需 `library.read`；支持分页和更新时间筛选 |
| 单条/批量读取 | `GET /v1/my/library/{series_id}`、`GET /v1/my/library/batch` | 批量最多 100 个，未在书库的作品被省略 |
| 添加 | `POST /v1/my/library/{series_id}` | OAuth 需 `library.write`；成功 201 |
| 更新 | `PATCH /v1/my/library/{series_id}` | 仅发送需要修改的字段；成功 200，`data` 为 boolean |
| 删除 | `DELETE /v1/my/library/{series_id}` | OAuth 需 `library.write`；成功 200，`data` 为 boolean |
| 批量添加/更新 | `POST /v1/my/library/batch` | 最多 100 条，整批原子；已存在条目只更新提供字段，`insert_only` 可跳过既有项 |

`PUT` 当前与 `PATCH` 都允许部分字段，文档明确未来可能不同；更新优先使用 `PATCH`，避免依赖 PUT 的当前偶然语义。批量写入拒绝 merged/inactive series，不能直接对旧匹配 ID 盲重试。

| 字段 | 约束/映射注意 |
| --- | --- |
| `state` | `considering`、`completed`、`dropped`、`paused`、`plan_to_read`、`reading`、`rereading`；需要明确本地状态映射，不应吞掉 considering/rereading |
| `progress_chapter` / `progress_volume` | `number` 或 null，范围 0–10000，schema 未限定整数；0 被存储为 null，不能将 null 当作错误丢弃 |
| `rating` | `number` 或 null，范围 0–100；0 表示未评分并存储为 null，不能把 0 作为最低有效评价 |
| `number_of_rereads` | `number` 或 null，范围 0–1000；0 存储为 null |
| `start_date` / `finish_date` | 请求优先 `YYYY-MM-DD`；兼容 date-time 时按原字符串取日并丢弃时区/时间；v1 响应仍为当天 `T00:00:00.000Z`，不要按设备时区转成前一天 |
| 其他字段 | `note`、`read_link`、`priority`、`is_private`；更新进度时不应提交默认值覆盖这些远端设置 |

`/v1/my/profile.rating_steps` 为 1、5、10、20、25 或 null；API 评分仍统一是 0–100。客户端应尊重用户评分步长并明确与本地量表的转换，不能仅依据网站显示星数推断传入值。[OpenAPI profile 和 library schema](https://mangabaka.org/api.json)

## 分页、增量与冲突

- v1 书库 `page` 从 1 开始、最大 10000；`limit` 为 1–100，默认 12。v2 默认 24。返回 `pagination.count/page/limit/next/previous`，`next = null` 表示结束。[OpenAPI](https://mangabaka.org/api.json)
- v1/v2 书库支持 `updated_after`（含边界）、`updated_before`（含边界）和 `updated_at_asc/desc` 排序；可用于增量拉取。然而本次 v1 单条/列表以及 `LibraryEntryV2.entry` schema 未声明每条 `updated_at`，尽管查询说明引用它。不能预设响应必定携带可靠游标，应先联调实际响应。[OpenAPI](https://mangabaka.org/api.json)
- 规范未提供版本号/ETag 条件更新、写入冲突检测或删除 tombstone 流。增量拉取不能据此保证发现删除；首版应保留全量核对，重试只发送本次变更字段，避免旧快照覆盖远端评分和备注。这是依据接口缺口提出的实现建议，并非服务端已保证的策略。
- 搜索另有 `page * limit <= 12000` 的结果窗口限制，超出返回 400，不能与书库分页上限混为一谈。[OpenAPI search 参数](https://mangabaka.org/api.json)

## 限流与实际探测

[官方 API 总览](https://mangabaka.org/data/api)规定按 IP 使用 leaky bucket：搜索未缓存请求 30 次/分钟；默认请求 180 次/分钟；`/my/*` 永不缓存并使用默认额度。超限返回 429。同 kind 的额度共享，不能按作品分别计数。实现应合并连续进度更新并退避，不能依赖 CDN 缓存减轻书库写入限流。本次没有触发限流或实测 `Retry-After`。

2026-10-21 使用不带账号信息的 `curl` GET，实际结果：

| 请求 | HTTP / 观察 |
| --- | --- |
| `https://api.mangabaka.org/v1/series/search?q=one%20piece&limit=1` | 200；首条为 ID 377 的 ONE PIECE；返回分页对象和下一页 URL |
| `https://api.mangabaka.org/v1/my/profile` | 401，`No session found` |
| `https://api.mangabaka.org/v1/my/library?limit=1` | 401，`No session found` |
| `https://mangabaka.org/.well-known/openid-configuration` | 返回前述 OAuth/OIDC 配置 JSON |

这些结果仅验证公共读取可用和个人接口要求授权；不证明 PAT/OAuth token 可用、权限组合正确或读写往返成功。没有对生产接口发送 POST、PUT、PATCH、DELETE，也没有使用用户现有凭据。

## 接入验收边界

1. 维护者注册 Native/Public 应用，验证 PKCE、Android 回调和 refresh token；不要把 discovery 的认证方式列表当作唯一依据。
2. 在专用测试账号上验证读取空书库/分页、添加、只改章节、评分清除、状态、卷、日期和删除，核对部分更新是否保留备注/隐私属性。
3. 核对评分步长、浮点进度及 0/null 语义，确认更新时间字段和删除后的全量核对策略；记录 401/403/404/429 处理。
4. 通过以上验收后才可把 #260 的“完整追踪”标记完成。现阶段结论是接口能力足够、原生授权设计明确，但真实账号往返尚未验证。
