# 技术设计

## Boundary

只调整 `DiscoverHeroCarousel` 的底部融合层。图片请求、Crossfade、卡片内容和滚动状态保持不变。

## Approach

将当前以 `pageBackground` 高 alpha 终止的纯色渐变收敛为低 alpha 的半透明 scrim，并把最终不透明背景限制在真正承载 bottom content 的区域；必要时抽取可测试的 gradient spec，统一 detached/non-detached 两条路径。不得为每张动态图片增加主色分析或额外 bitmap 缓存。

## Fallback

Panorama 关闭或图片不可用时保留现有主题背景和文字对比度；任何视觉调整都不改变卡片内容的布局高度。
