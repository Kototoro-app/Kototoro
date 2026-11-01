package org.skepsun.kototoro.local.epub

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import org.skepsun.kototoro.R

/**
 * Property-based test for parse failure error messages.
 * 
 * Feature: epub-reader-improvements, Property 34: Parse Failure Error Messages
 * Validates: Requirements 10.1
 * 
 * Property: For any EPUB parsing failure, a user-friendly error message SHALL be displayed
 */
class ParseFailureErrorMessagesPropertyTest : StringSpec({
    
    "all parse errors have non-empty user messages" {
        checkAll(100, arbParseError()) { error ->
            error.userMessageRes shouldNotBe 0
        }
    }
    
    "all parse errors have non-empty technical messages" {
        checkAll(100, arbParseError()) { error ->
            error.message.shouldNotBeBlank()
        }
    }
    
"invalid format errors contain format information" {
        repeat(100) {
            val error = EpubError.ParseError.InvalidFormat()
            error.userMessageRes shouldBe R.string.epub_error_invalid_format
            error.message shouldContain "format"
        }
    }
    
    "corrupted file errors contain filename" {
        checkAll(100, Arb.string(1..50)) { fileName ->
            val error = EpubError.ParseError.CorruptedFile(fileName)
            error.userMessageArgs shouldContain fileName
            error.message shouldContain fileName
        }
    }
    
    "missing components errors contain component name" {
        checkAll(100, Arb.string(1..30)) { component ->
            val error = EpubError.ParseError.MissingComponents(component)
            error.userMessageArgs shouldContain component
            error.message shouldContain component
        }
    }
    
    "unsupported version errors contain version info" {
        checkAll(100, Arb.string(1..10)) { version ->
            val error = EpubError.ParseError.UnsupportedVersion(version)
            error.userMessageArgs shouldContain version
            error.message shouldContain version
        }
    }
    
    "malformed HTML errors contain chapter index" {
        checkAll(100, Arb.int(0..999)) { chapterIndex ->
            val error = EpubError.ParseError.MalformedHtml(chapterIndex)
            error.userMessageArgs shouldContain chapterIndex
            error.message shouldContain chapterIndex.toString()
        }
    }
    
    "parse errors preserve cause exceptions" {
        checkAll(100, Arb.string(1..50)) { message ->
            val cause = Exception(message)
            val error = EpubError.ParseError.CorruptedFile("test.epub", cause)
            error.cause shouldBe cause
        }
    }
})

/**
 * Arbitrary generator for parse errors
 */
private fun arbParseError(): Arb<EpubError.ParseError> = arbitrary {
    Arb.choice(
        arbitrary { EpubError.ParseError.InvalidFormat() },
        arbitrary { 
            EpubError.ParseError.CorruptedFile(
                Arb.string(1..50).bind()
            ) 
        },
        arbitrary { 
            EpubError.ParseError.MissingComponents(
                Arb.string(1..30).bind()
            ) 
        },
        arbitrary { 
            EpubError.ParseError.UnsupportedVersion(
                Arb.string(1..10).bind()
            ) 
        },
        arbitrary { 
            EpubError.ParseError.MalformedHtml(
                Arb.int(0..999).bind()
            ) 
        }
    ).bind()
}
