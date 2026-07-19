# Issue 409：修复收藏作品 projections 显示不全

## Goal

修复 1.6.3 更新后收藏作品只显示部分 projection 的问题，确保已有多 projection 数据完整呈现且迁移不丢数据。

## Requirements

- 定位 projection 数量减少发生在数据库迁移、EntityGraph 查询、收藏聚合还是 UI 列表限制。
- 多 projection 收藏作品完整显示，不能用固定数量截断或只保留首选 projection。
- 兼容 Migration 74 → 75、内容类型隔离、preferred projection 和 detached projection 状态。
- 对明确损坏或未知归属数据提供可诊断路径，不静默删除。

## Acceptance Criteria

- [ ] 7 个 projection 的收藏作品可显示 7 个，其他数量同样不被截断。
- [ ] 升级/重启/刷新后数量稳定，单 projection 和多作品列表不回归。
- [ ] 查询、映射和 UI 均不隐藏合法 projection；必要的迁移修复有测试和日志。
- [ ] 数据层测试、迁移/schema 验证、编译和 `git diff --check` 通过。

## Notes

现有 `07-16-work-content-type-isolation` 已包含内容类型隔离和 projection 修复基础，本子任务必须先审计其实际完成状态。
