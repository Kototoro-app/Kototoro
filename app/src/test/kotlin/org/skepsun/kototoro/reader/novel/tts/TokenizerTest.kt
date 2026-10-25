package org.skepsun.kototoro.reader.novel.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.novel.tts.model.TokenType

class TokenizerTest {

    private val text = "“你好。”张三说。\n天色渐暗。\n“走吧。”张三说。\n“等等。”李四道。"

    @Test
    fun dialogueKeepsTheChosenVoiceWithoutASpeakerPool() {
        val dialogue = Tokenizer.tokenize(text).filter { it.type == TokenType.DIALOGUE }
        assertEquals(3, dialogue.size)
        // No voice the engine does not have: the user's own voice is used for every line.
        dialogue.forEach { assertNull(it.speaker) }
    }

    @Test
    fun aSpeakerKeepsOneVoiceAcrossParagraphs() {
        val dialogue = Tokenizer.tokenize(text, listOf("a", "b")).filter { it.type == TokenType.DIALOGUE }
        val voices = dialogue.map { requireNotNull(it.speaker).voiceId }
        assertEquals(listOf("a", "a", "b"), voices)
    }
}
