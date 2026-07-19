# 执行计划

- [x] 读取 backend/frontend 规范和现有内容类型隔离任务。
- [x] 通过代码与测试确定 reading source 缺失发生在缓存写入、读取还是状态合并。
- [x] 修复对应单一边界，加入请求 identity 和初始加载回归测试。
- [x] 验证首次打开、缓存命中、刷新、projection 切换和页面重建。
- [x] 运行相关 JVM 测试、Debug 编译和 `git diff --check`。

## Verification

- `:app:testDebugUnitTest --tests "org.skepsun.kototoro.details.domain.DetailsLoadUseCaseTest"`：通过，3 项。
- `:app:testDebugUnitTest --tests "org.skepsun.kototoro.details.*"`：通过，30 项。
- `:app:compileDebugKotlin`、`:app:processDebugResources`：通过。
- `git diff --check`：通过。
