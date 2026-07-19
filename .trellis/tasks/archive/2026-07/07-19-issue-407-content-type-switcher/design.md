# 技术设计

先定位主界面顶栏内容类型过滤器及当前 `GlassDropdownMenu`/root overlay 调用。将内容类型列表建模为固定的 `VIDEO, MANGA, NOVEL` 顺序，中心项由当前类型确定；展开面板使用已有同窗 host 和锚点测量，不为按钮自身包一层视觉 Surface。

手势状态与位置映射抽取为可测试的纯函数或小型状态模型：长按进入展开，拖拽按横向位置选择，松开提交或取消。Backdrop 只由面板消费者采样已注册 source，Popup/host 不可用时使用既有静态 fallback。
