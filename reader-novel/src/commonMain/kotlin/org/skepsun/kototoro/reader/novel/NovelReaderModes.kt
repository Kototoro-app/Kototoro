package org.skepsun.kototoro.reader.novel

enum class NovelReaderThemePreset {
    PAPER,
    SEPIA,
    MOSS,
    SLATE,
}

enum class NovelPageTurnAnimation {
    SLIDE,
    SIMULATION,
}

/**
 * 阅读模式
 */
enum class ReadingMode {
    PAGED,      // 分页模式
    SCROLL,     // 滚动模式
    VERTICAL,   // 竖排模式（从右到左翻页）
}

/**
 * 文本方向
 */
enum class TextDirection {
    LTR,  // 从左到右
    RTL,  // 从右到左
    AUTO, // 按段落首个强方向字符自动判断（阿拉伯语、希伯来语段落为从右到左）
}
