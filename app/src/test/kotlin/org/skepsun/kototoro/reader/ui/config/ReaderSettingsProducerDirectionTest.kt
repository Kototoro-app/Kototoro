package org.skepsun.kototoro.reader.ui.config

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.ConnectivityManager
import androidx.preference.PreferenceManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import kotlin.time.Duration.Companion.seconds

/**
 * Live delivery of the continuous-horizontal reading direction.
 *
 * The horizontal scene reader takes its [org.skepsun.kototoro.reader.core.SceneReadingDirection]
 * from [ReaderSettings] collected out of [ReaderSettings.Producer]. The producer only re-publishes
 * when the changed AppSettings key is in its observed key set: a key missing there means the
 * direction switch flips in the options sheet (the sheet state is updated directly) while the
 * reader keeps the direction captured when the producer started — the user-visible
 * "从右向左阅读 开了不生效" symptom.
 *
 * This test drives the real toggle path: the preference change listener fires with the direction
 * key while the producer is active (the reader screen is open and collecting), which is exactly
 * how the switch behaves when the user flips it mid-reading.
 *
 * The barrier step (firing an observed key first) is load-bearing: it waits until the producer's
 * own initial republication has been processed, so the direction flip afterwards can only reach
 * the reader settings through the direction key itself — not by racing the initial republication
 * into re-reading the already-flipped preference.
 */
class ReaderSettingsProducerDirectionTest {

    private val context = mockk<Context>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val listenerSlot = slot<SharedPreferences.OnSharedPreferenceChangeListener>()
    private lateinit var listenerRegistered: CompletableDeferred<Unit>
    private lateinit var initialRepublicationProcessed: CompletableDeferred<Unit>
    private var isReversedPref = false
    private var isPagesNumbersPref = false

    private val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeEach
    fun setUp() {
        mockkStatic(PreferenceManager::class)
        every { PreferenceManager.getDefaultSharedPreferences(context) } returns preferences
        every { context.getString(any()) } returns ""
        every { context.getString(any(), any()) } returns ""
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockk<ConnectivityManager>()
        every { context.resources } returns mockk<Resources> {
            every { getStringArray(any()) } returns emptyArray()
        }
        every { preferences.contains(any()) } returns false
        every { preferences.getBoolean(any(), any()) } answers {
            when (firstArg<String>()) {
                AppSettings.KEY_READER_CONTINUOUS_HORIZONTAL_REVERSED -> isReversedPref
                AppSettings.KEY_PAGES_NUMBERS -> isPagesNumbersPref
                else -> secondArg()
            }
        }
        every { preferences.getInt(any(), any()) } answers { secondArg() }
        every { preferences.getLong(any(), any()) } answers { secondArg() }
        every { preferences.getFloat(any(), any()) } answers { secondArg() }
        every { preferences.getString(any(), any()) } answers { secondArg() }
        every { preferences.getStringSet(any(), any()) } answers {
            secondArg<Set<String>?>()?.toMutableSet()
        }
        every { preferences.edit() } returns editor

        listenerRegistered = CompletableDeferred()
        every { preferences.registerOnSharedPreferenceChangeListener(capture(listenerSlot)) } answers {
            listenerRegistered.complete(Unit)
        }
        every { preferences.unregisterOnSharedPreferenceChangeListener(any()) } returns Unit

        mockkStatic("org.skepsun.kototoro.core.util.ext.CoroutinesKt")
        every { processLifecycleScope } returns producerScope
    }

    @AfterEach
    fun tearDown() {
        producerScope.cancel()
        unmockkStatic("org.skepsun.kototoro.core.util.ext.CoroutinesKt")
        unmockkStatic(PreferenceManager::class)
    }

    @Test
    fun `flipping the direction preference reaches reader settings while the reader is open`() = runBlocking {
        val repository = mockk<ContentDataRepository> {
            every { observeColorFilter(any()) } returns flowOf(null)
        }
        val producer = ReaderSettings.Producer(flowOf(1L), AppSettings(context), repository)
        producer.value.isContinuousHorizontalReversed shouldBe false

        initialRepublicationProcessed = CompletableDeferred()
        // Hold the producer active for the whole test, exactly like the open reader screen does.
        // StateFlow conflation swallows equal-value republications, so the barrier key changes an
        // observable field (page numbers) to make the republication visible to this collector.
        val keepAlive = launch {
            producer.collect { value ->
                if (value.isPagesNumbersEnabled) initialRepublicationProcessed.complete(Unit)
            }
        }
        listenerRegistered.await()

        // Barrier: an already-observed key must flow through and flip a visible field. Once this
        // completes, the producer's initial republication has been fully processed.
        isPagesNumbersPref = true
        listenerSlot.captured.onSharedPreferenceChanged(preferences, AppSettings.KEY_PAGES_NUMBERS)
        withTimeout(5.seconds) {
            initialRepublicationProcessed.await()
        }
        producer.value.isContinuousHorizontalReversed shouldBe false

        // The user flips the "Read right to left" switch of the horizontal mode.
        isReversedPref = true
        listenerSlot.captured.onSharedPreferenceChanged(
            preferences,
            AppSettings.KEY_READER_CONTINUOUS_HORIZONTAL_REVERSED,
        )

        withTimeout(5.seconds) {
            producer.first { it.isContinuousHorizontalReversed }
        }
        keepAlive.cancel()
    }
}
