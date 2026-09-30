package org.skepsun.kototoro.core.db

import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BaseApp registers this production observer set before collecting local imports. An observer
 * referencing a removed table aborts that startup coroutine and leaves copied files unindexed.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class ApplicationDatabaseObserversTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var observers: Set<@JvmSuppressWildcards InvalidationTracker.Observer>

    @Test
    fun startupObserversCanRegisterAgainstCurrentSchema() {
        hiltRule.inject()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        try {
            val failures = observers.mapNotNull { observer ->
                runCatching { db.invalidationTracker.addObserver(observer) }
                    .exceptionOrNull()
                    ?.let { "${observer.javaClass.simpleName}: ${it.message}" }
            }
            assertTrue("Startup observer registration failed: $failures", failures.isEmpty())
        } finally {
            db.close()
        }
    }
}
