# 实施计划

1. 补齐 `values-zh-rCN/strings.xml` 的 Background style 文案。
2. 修改 `KototoroTheme.resolveComposeColorScheme`，使 Dynamic Artwork Blur 在浅色系统模式下使用暗背景高对比前景。
3. 修改 `KototoroSearchOverlay`，静止时不组合删除背景，滑动时显示对应方向的删除背景。
4. 复用主顶栏的按钮容器、尺寸和间距；动态图片背景下使用不透明搜索层，并保持搜索滚动与主界面隔离。
5. 让图片背景下的顶栏/底栏沉浸色使用深色基底，修复搜索行图标前景色，并将编辑框高度与主顶栏统一。
6. 禁止搜索顶栏玻璃容器采样主界面的运行时 Haze 内容。
7. 提取共享动态封面背景容器并接入设置 Activity。
8. 检查差异、资源 key 和 Kotlin 编译。

## 验证命令

- `./gradlew :app:processReleaseResources`
- `./gradlew :app:compileReleaseKotlin`
- `git diff --check`

## 风险点

- 主题色彩分支若只改变背景而没有同步 `onSurface` 等前景色，会重现浅色模式黑字问题。
- `SwipeToDismissBoxValue.Settled` 必须保持为空背景，不能把默认方向当作真实滑动方向。
