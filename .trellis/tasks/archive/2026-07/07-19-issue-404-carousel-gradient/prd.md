# Issue 404：优化浏览页轮播卡片底部渐变

## Goal

开启图标背景时，浏览页顶部轮播卡片底部的渐变应与动态图片背景自然融合，不出现明显的纯色断层，同时保持底部文字和操作的可读性。

## Confirmed Facts

- `DiscoverHeroCarousel` 在 `DiscoverHeroCarousel.kt:354-435` 绘制多层以 `pageBackground` 为基色的垂直/水平渐变。
- 背景图片由 `Crossfade` 和 `AsyncImage` 动态切换，卡片可以滚动，因此不能依赖静态图片取色或固定图片内容。

## Requirements

- 降低纯色渐变在图片背景上的突兀感，优先采用半透明、主题中性的叠加方式。
- 保留图片切换、滚动、Panorama 开关和底部内容布局。
- 保持文字对比度，并兼容明暗主题及无图片/加载中状态。

## Acceptance Criteria

- [ ] 图片切换和轮播滚动过程中，底部渐变无明显纯色硬边。
- [ ] 底部标题/操作在典型浅色和深色图片上仍可读。
- [ ] Panorama 关闭、加载失败和非轮播页面行为不回归。
- [ ] 有纯函数或 Compose 回归覆盖关键渐变参数；完成编译和 `git diff --check`。

## Out Of Scope

- 不实现逐帧图片主色提取。
- 不重做轮播卡片尺寸、数据加载或滚动交互。
