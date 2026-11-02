package org.skepsun.kototoro.reader.novel.tts

import org.skepsun.kototoro.reader.novel.tts.model.Speaker
import org.skepsun.kototoro.reader.novel.tts.model.Token
import org.skepsun.kototoro.reader.novel.tts.model.TokenType
import java.security.MessageDigest

object Tokenizer {

    private fun md5ToLong(input: String): Long {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray())
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (bytes[i].toLong() and 0xFFL)
        }
        return result
    }

    private class SpeakerResolver {
        private val speakerMap = mutableMapOf<String, Speaker>()
        private var voiceIndex = 0

        fun resolve(name: String?, voices: List<String>): Speaker? {
            if (name == null || name.isBlank() || voices.isEmpty()) return null
            return speakerMap.getOrPut(name) {
                // 轮询分配不同的音色以区分不同角色
                val voiceId = voices[voiceIndex % voices.size]
                voiceIndex++
                Speaker(name, voiceId)
            }
        }
    }

    /**
     * 将长文本切分为细粒度的 Token。
     * - 先按段落分 (\n\n)
     * - 按标点断句 (。！？)
     * - 针对中文长句按 (，、；) 继续切分
     * - 自动拆分对话与旁白
     * - 在合理的空隙处插入 PAUSE
     *
     * [speakerVoices] 是当前引擎实际可用的音色，按出场顺序轮流分给对话中识别出的说话人；为空（默认）时不分配，
     * 引擎使用用户选择的音色。过去这里写死了三个 Azure 音色名，HTTP 引擎会把它们代入任何朗读源的 `{{voice}}`，
     * 非 Edge/Azure 的源因此在对话句上请求失败或换错声音。
     */
    fun tokenize(text: String, speakerVoices: List<String> = emptyList()): List<Token> {
        val tokens = mutableListOf<Token>()
        var globalSearchOffset = 0
        // 同一角色在整章中保持同一音色（原先每段新建，换段即换声音）。
        val resolver = SpeakerResolver()

        val paragraphs = text.split(Regex("(\\r?\\n)+"))
        for (p in paragraphs) {
            if (p.isBlank()) continue

            val pStart = text.indexOf(p, globalSearchOffset)
            val actualPStart = if (pStart >= 0) pStart else globalSearchOffset
            var pSearchOffset = actualPStart

            // We use Regex to match sentences.
            // A closing quote stays with its sentence: “你好。”张三说。 is a line of dialogue and a narration, not a
            // dialogue opening and a "dialogue" made of the closing quote and the narration.
            val sentenceRegex = Regex("([^。！？.!?]+[。！？.!?]*[”」』\"]?)")
            val sentences = sentenceRegex.findAll(p).map { it.value }.toList()
            val finalSentences = if (sentences.isEmpty()) listOf(p) else sentences

            for (s in finalSentences) {
                // 用于提取对话段落后文本中潜在说话人名称正则
                val speakerRegex = Regex("([^\\s“”「」『』\"]{1,4})(说|问|道|喊)")

                val sStart = text.indexOf(s, pSearchOffset)
                val actualSStart = if (sStart >= 0) sStart else pSearchOffset
                var sSearchOffset = actualSStart

                // For long sentences, split by comma/semicolon/dunhao
                val parts = if (s.length > 30) {
                    val partRegex = Regex("([^，、；,;]+[，、；,;]*)")
                    val subParts = partRegex.findAll(s).map { it.value }.toList()
                    if (subParts.isNotEmpty()) subParts else listOf(s)
                } else {
                    listOf(s)
                }

                for (part in parts) {
                    if (part.isBlank()) continue

                    val partStart = text.indexOf(part, sSearchOffset)
                    val actualPartStart = if (partStart >= 0) partStart else sSearchOffset

                    // Parse dialogue
                    val isDialogue = part.contains("“") || part.contains("”")

                    var speaker: Speaker? = null
                    val cleanText: String

                    if (isDialogue) {
                        cleanText = part.removeSurrounding("“", "”").replace("“", "").replace("”", "")

                        // 寻找原文中紧随对话后文的说话人标识，如“你好”张三说。
                        val afterTextIndex = actualPartStart + part.length
                        if (afterTextIndex < text.length) {
                            val afterStr = text.substring(afterTextIndex, (afterTextIndex + 10).coerceAtMost(text.length))
                            val speakerName = speakerRegex.find(afterStr)?.groupValues?.get(1)
                            speaker = resolver.resolve(speakerName, speakerVoices)
                        }
                    } else {
                        cleanText = part
                    }

                    tokens += Token(
                        id = md5ToLong(cleanText + actualPartStart),
                        text = cleanText,
                        type = if (isDialogue) TokenType.DIALOGUE else TokenType.NARRATION,
                        speaker = speaker,
                        range = IntRange(actualPartStart, actualPartStart + part.length - 1)
                    )
                    sSearchOffset = actualPartStart + part.length
                }
                pSearchOffset = sSearchOffset
            }
            globalSearchOffset = actualPStart + p.length

            // Insert artificial pause at end of paragraph
            val pauseEnd = globalSearchOffset
            tokens += Token(
                id = System.nanoTime(),
                text = "",
                type = TokenType.PAUSE,
                range = IntRange(pauseEnd, pauseEnd),
                durationHintMs = 500
            )
        }

        return tokens
    }
}
