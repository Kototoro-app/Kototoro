# 技术设计

检查 `DetailsLoadUseCase`、`DetailsViewModel` 的缓存快照/网络结果合并，以及 `readingSourceOptions` 的唯一构建点。将当前 projection identity 作为缓存 key 和状态门卫；初始化状态先发布与请求匹配的缓存 source，异步网络结果只更新同一 identity，旧请求不得覆盖新页面。UI 继续消费统一过滤后的 source 集合，不在 Compose 层补读数据库。

若问题来自缓存未存储 source 元数据，应修正缓存模型或写入边界；若来自 Flow 初始空值覆盖，应修正状态合并顺序。保留现有刷新语义和内容类型过滤。
