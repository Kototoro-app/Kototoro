package org.skepsun.kototoro.core.db

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.room.Room
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.util.ext.processLifecycleScope

fun MangaDatabase(context: Context): MangaDatabase = Room
    .databaseBuilder(context, MangaDatabase::class.java, "kototoro-db")
    .addMigrations(*getDatabaseMigrations(context))
    .fallbackToDestructiveMigrationOnDowngrade()
    .addCallback(DatabasePrePopulateCallback(context.resources))
    .build()

fun InvalidationTracker.removeObserverAsync(observer: InvalidationTracker.Observer) {
    val scope = processLifecycleScope
    if (scope.isActive) {
        processLifecycleScope.launch(Dispatchers.Default, CoroutineStart.ATOMIC) {
            removeObserver(observer)
        }
    }
}
