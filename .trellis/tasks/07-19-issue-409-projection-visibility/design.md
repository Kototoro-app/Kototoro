# 技术设计

沿 `FavouritesRepository → EntityOrganizeRepository/EntityGraphDao → UI model` 追踪完整 projection 集合，检查所有 `limit`、去重、preferred fallback 和内容类型过滤。使用 entity/projection identity 作为唯一去重键，不按标题或显示名称合并。若 74 → 75 迁移已拆分或重建 binding，增加迁移后完整性检查，并复用现有 ledger/repair 流程恢复可证明归属的数据。

UI 只渲染数据层提供的完整集合；不要在 UI 中扩大查询或把被过滤的异类 projection 重新加入。未知类型和无归属数据进入既有 repair/diagnostic 路径。
