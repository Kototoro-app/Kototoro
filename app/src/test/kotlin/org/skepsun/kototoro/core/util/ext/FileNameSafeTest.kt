package org.skepsun.kototoro.core.util.ext

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `toFileNameSafe()` 的结果会直接当作下载目录名使用。旧实现对纯 emoji / 纯标点 /
 * 非受支持语种的标题会产出「只剩一条下划线」甚至空串，配合 UniFile「创建失败返回 null」
 * 与下游裸 `checkNotNull`，就得到了 issue #511 里那句 "Required value was null."。
 */
class FileNameSafeTest {

    @Test
    fun `normal title keeps its words`() {
        assertTrue("One Piece".toFileNameSafe().contains("One"))
        assertTrue("One Piece".toFileNameSafe().contains("Piece"))
    }

    @Test
    fun `chinese title is preserved`() {
        assertTrue("海贼王".toFileNameSafe().contains("海贼王"))
    }

    @Test
    fun `symbol only titles still produce a usable name`() {
        for (title in listOf("", "!!!", "??? ???", "...", "🔥🔥🔥", "———", "***")) {
            val name = title.toFileNameSafe()
            assertFalse(name.isBlank(), "blank file name produced for \"$title\"")
            assertFalse(name.startsWith("."), "file name must not start with a dot for \"$title\": $name")
        }
    }

    @Test
    fun `cjk brackets with digits keep the digits`() {
        // issue #511 用户的实际场景：标题几乎全是符号，只剩数字
        val name = "〔自定义〕 123 3179".toFileNameSafe()
        assertTrue(name.contains("123"), "digits were dropped: $name")
    }
}
