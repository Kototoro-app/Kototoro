# 技术设计

先沿 `ImageLoader` 配置、`DiscoverHeroCarousel`/`AnimatedPanoramaBackdrop`、Backdrop host 和 Activity/route 生命周期建立资源图，结合最近提交和可用设备做基线。对每个候选点记录图片请求尺寸、同时存活数量、Crossfade/动画生命周期和离场路由释放时机。

实现只落在已证实的边界：例如把请求尺寸绑定到容器约束、取消离场图片请求、避免重复 Backdrop source 或限制动画同时保留的 bitmap。若只能从日志确认系统在绘制时内存紧张而不能确认单一根因，则先增加可观测性和局部上限，不做全局缓存清空。
