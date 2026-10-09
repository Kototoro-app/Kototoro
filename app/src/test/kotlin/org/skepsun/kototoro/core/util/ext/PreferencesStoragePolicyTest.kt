package org.skepsun.kototoro.core.util.ext

import android.content.SharedPreferences
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingColor
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingStyle

/**
 * Regression coverage for https://github.com/Kototoro-app/Kototoro/issues/573
 *
 * Settings-backup restore historically promoted every Int preference to Long
 * (AppSettings.upsertAll's old private putAll did `is Int -> putLong(...)`), so
 * `NovelReaderActivity.onCreate()` crashed with
 * `ClassCastException: java.lang.Long cannot be cast to java.lang.Integer`
 * on the `getInt()` reads of `novel_active_marking_*`.
 *
 * The tests pin the unified storage policy behind [preferenceStorageOp] — the
 * policy that both SharedPreferences writers now share — and the self-healing
 * read of [getSafeInt].
 */
class PreferencesStoragePolicyTest {

    // --- preferenceStorageOp: the shared backup-restore numeric policy ---

    @Test
    fun `restored Int stays Int`() {
        val (type, value) = preferenceStorageOp(4)!!
        type shouldBe PreferenceStorageType.INT
        value shouldBe 4
    }

    @Test
    fun `restored small Long that fits in Int is stored as Int`() {
        // org.json may parse a small integer as Long; it must still be stored as
        // Int so plain getInt() readers (novel marking settings) never crash.
        val (type, value) = preferenceStorageOp(4L)!!
        type shouldBe PreferenceStorageType.INT
        value shouldBe 4
    }

    @Test
    fun `restored epoch timestamp Long stays Long`() {
        val epoch = 1_756_000_000_000L // well beyond Int.MAX_VALUE
        val (type, value) = preferenceStorageOp(epoch)!!
        type shouldBe PreferenceStorageType.LONG
        value shouldBe epoch
    }

    @Test
    fun `Long boundary stays Int up to Int MAX and Long above`() {
        preferenceStorageOp(Int.MAX_VALUE.toLong())!!.first shouldBe PreferenceStorageType.INT
        preferenceStorageOp(Int.MAX_VALUE.toLong() + 1)!!.first shouldBe PreferenceStorageType.LONG
        preferenceStorageOp(Int.MIN_VALUE.toLong())!!.first shouldBe PreferenceStorageType.INT
        preferenceStorageOp(Int.MIN_VALUE.toLong() - 1)!!.first shouldBe PreferenceStorageType.LONG
    }

    @Test
    fun `re-restoring the same backup never changes storage type`() {
        // A backup may hand back the same integer as Int or Long between runs;
        // both must converge to Int and then stay Int.
        val first = preferenceStorageOp(3L)!!
        first.first shouldBe PreferenceStorageType.INT
        preferenceStorageOp(first.second)!!.first shouldBe PreferenceStorageType.INT
        // A genuine timestamp converges to Long and stays Long.
        val epoch = 1_756_000_000_000L
        val second = preferenceStorageOp(epoch)!!
        second.first shouldBe PreferenceStorageType.LONG
        preferenceStorageOp(second.second)!!.first shouldBe PreferenceStorageType.LONG
    }

    @Test
    fun `non integral values keep their storage type`() {
        preferenceStorageOp(true)!!.first shouldBe PreferenceStorageType.BOOLEAN
        preferenceStorageOp(1.5f)!!.first shouldBe PreferenceStorageType.FLOAT
        preferenceStorageOp(1.5)!!.first shouldBe PreferenceStorageType.FLOAT
        preferenceStorageOp("some key")!!.first shouldBe PreferenceStorageType.STRING
        preferenceStorageOp(emptySet<String>())!!.first shouldBe PreferenceStorageType.STRING_SET
        preferenceStorageOp(null).shouldBeNull()
    }

    // --- getSafeInt: self-healing read for stores already polluted by old restores ---

    @Test
    fun `getSafeInt recovers a Long-stored marking value and rewrites it as Int`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs = mockk<SharedPreferences>()
        every { prefs.getInt(KEY, any()) } throws ClassCastException()
        every { prefs.all } returns mapOf(KEY to 2L)
        every { prefs.edit() } returns editor

        prefs.getSafeInt(KEY, 0) shouldBe 2
        verify { editor.putInt(KEY, 2) }
    }

    @Test
    fun `getSafeInt falls back to the default for a Long out of Int range`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs = mockk<SharedPreferences>()
        every { prefs.getInt(KEY, any()) } throws ClassCastException()
        every { prefs.all } returns mapOf(KEY to 9_000_000_000_000L)
        every { prefs.edit() } returns editor

        prefs.getSafeInt(KEY, 7) shouldBe 7 // no silent Int overflow wrap
    }

    @Test
    fun `getSafeInt returns an Int-stored value without touching the editor`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs = mockk<SharedPreferences>()
        every { prefs.getInt(KEY, any()) } returns 3
        every { prefs.edit() } returns editor

        prefs.getSafeInt(KEY, 0) shouldBe 3
        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun `getSafeInt returns the default when the key is absent`() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getInt(KEY, any()) } returns 5

        prefs.getSafeInt(KEY, 5) shouldBe 5
    }

    // --- novel marking enum validation (reader-side guard against bad restore data) ---

    @Test
    fun `unknown marking ids fall back to the safe default`() {
        NovelMarkingColor.fromId(99) shouldBe NovelMarkingColor.YELLOW
        NovelMarkingColor.fromId(NovelMarkingColor.YELLOW.id) shouldBe NovelMarkingColor.YELLOW
        NovelMarkingStyle.fromId(99) shouldBe NovelMarkingStyle.UNDERLINE
        NovelMarkingStyle.fromId(NovelMarkingStyle.UNDERLINE.id) shouldBe NovelMarkingStyle.UNDERLINE
    }

    private companion object {
        const val KEY = "novel_active_marking_color"
    }
}
