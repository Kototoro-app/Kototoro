package org.skepsun.kototoro.core.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import org.skepsun.kototoro.core.db.migrations.Migration10To11
import org.skepsun.kototoro.core.db.migrations.Migration11To12
import org.skepsun.kototoro.core.db.migrations.Migration12To13
import org.skepsun.kototoro.core.db.migrations.Migration13To14
import org.skepsun.kototoro.core.db.migrations.Migration14To15
import org.skepsun.kototoro.core.db.migrations.Migration15To16
import org.skepsun.kototoro.core.db.migrations.Migration16To17
import org.skepsun.kototoro.core.db.migrations.Migration17To18
import org.skepsun.kototoro.core.db.migrations.Migration18To19
import org.skepsun.kototoro.core.db.migrations.Migration19To20
import org.skepsun.kototoro.core.db.migrations.Migration34To35
import org.skepsun.kototoro.core.db.migrations.Migration37To38
import org.skepsun.kototoro.core.db.migrations.Migration38To39
import org.skepsun.kototoro.core.db.migrations.Migration39To40
import org.skepsun.kototoro.core.db.migrations.Migration40To41
import org.skepsun.kototoro.core.db.migrations.Migration41To42
import org.skepsun.kototoro.core.db.migrations.Migration42To43
import org.skepsun.kototoro.core.db.migrations.Migration43To44
import org.skepsun.kototoro.core.db.migrations.Migration44To45
import org.skepsun.kototoro.core.db.migrations.Migration45To46
import org.skepsun.kototoro.core.db.migrations.Migration46To47
import org.skepsun.kototoro.core.db.migrations.Migration47To48
import org.skepsun.kototoro.core.db.migrations.Migration48To49
import org.skepsun.kototoro.core.db.migrations.Migration54To55
import org.skepsun.kototoro.core.db.migrations.Migration55To56
import org.skepsun.kototoro.core.db.migrations.Migration56To57
import org.skepsun.kototoro.core.db.migrations.Migration57To58
import org.skepsun.kototoro.core.db.migrations.Migration58To59
import org.skepsun.kototoro.core.db.migrations.Migration59To60
import org.skepsun.kototoro.core.db.migrations.Migration60To61
import org.skepsun.kototoro.core.db.migrations.Migration61To62
import org.skepsun.kototoro.core.db.migrations.Migration62To63
import org.skepsun.kototoro.core.db.migrations.Migration63To64
import org.skepsun.kototoro.core.db.migrations.Migration64To65
import org.skepsun.kototoro.core.db.migrations.Migration65To66
import org.skepsun.kototoro.core.db.migrations.Migration66To67
import org.skepsun.kototoro.core.db.migrations.Migration67To68
import org.skepsun.kototoro.core.db.migrations.Migration68To69
import org.skepsun.kototoro.core.db.migrations.Migration69To70
import org.skepsun.kototoro.core.db.migrations.Migration70To71
import org.skepsun.kototoro.core.db.migrations.Migration71To72
import org.skepsun.kototoro.core.db.migrations.Migration72To73
import org.skepsun.kototoro.core.db.migrations.Migration73To74
import org.skepsun.kototoro.core.db.migrations.Migration74To75
import org.skepsun.kototoro.core.db.migrations.Migration75To76
import org.skepsun.kototoro.core.db.migrations.Migration76To77
import org.skepsun.kototoro.core.db.migrations.Migration77To78
import org.skepsun.kototoro.core.db.migrations.Migration78To79
import org.skepsun.kototoro.core.db.migrations.Migration79To80
import org.skepsun.kototoro.core.db.migrations.Migration80To81
import org.skepsun.kototoro.core.db.migrations.Migration81To82
import org.skepsun.kototoro.core.db.migrations.Migration82To83
import org.skepsun.kototoro.core.db.migrations.Migration1To2
import org.skepsun.kototoro.core.db.migrations.Migration20To21
import org.skepsun.kototoro.core.db.migrations.Migration21To22
import org.skepsun.kototoro.core.db.migrations.Migration22To23
import org.skepsun.kototoro.core.db.migrations.Migration23To24
import org.skepsun.kototoro.core.db.migrations.Migration24To23
import org.skepsun.kototoro.core.db.migrations.Migration24To25
import org.skepsun.kototoro.core.db.migrations.Migration25To26
import org.skepsun.kototoro.core.db.migrations.Migration26To27
import org.skepsun.kototoro.core.db.migrations.Migration27To28
import org.skepsun.kototoro.core.db.migrations.Migration28To29
import org.skepsun.kototoro.core.db.migrations.Migration29To30
import org.skepsun.kototoro.core.db.migrations.Migration30To31
import org.skepsun.kototoro.core.db.migrations.Migration31To32
import org.skepsun.kototoro.core.db.migrations.Migration32To33
import org.skepsun.kototoro.core.db.migrations.Migration33To34
import org.skepsun.kototoro.core.db.migrations.Migration2To3
import org.skepsun.kototoro.core.db.migrations.Migration3To4
import org.skepsun.kototoro.core.db.migrations.Migration4To5
import org.skepsun.kototoro.core.db.migrations.Migration5To6
import org.skepsun.kototoro.core.db.migrations.Migration6To7
import org.skepsun.kototoro.core.db.migrations.Migration7To8
import org.skepsun.kototoro.core.db.migrations.Migration8To9
import org.skepsun.kototoro.core.db.migrations.Migration9To10

fun getDatabaseMigrations(context: Context): Array<Migration> = arrayOf(
    Migration1To2(),
    Migration2To3(),
    Migration3To4(),
    Migration4To5(),
    Migration5To6(),
    Migration6To7(),
    Migration7To8(),
    Migration8To9(),
    Migration9To10(),
    Migration10To11(),
    Migration11To12(),
    Migration12To13(),
    Migration13To14(),
    Migration14To15(),
    Migration15To16(),
    Migration16To17(context),
    Migration17To18(),
    Migration18To19(),
    Migration19To20(),
    Migration20To21(),
    Migration21To22(),
    Migration22To23(),
    Migration23To24(),
    Migration24To23(),
    Migration24To25(),
    Migration25To26(),
    Migration26To27(),
    Migration27To28(),
    Migration28To29(),
    Migration29To30(),
    Migration30To31(),
    Migration31To32(),
    Migration32To33(),
    Migration33To34(),
    Migration34To35(),
    org.skepsun.kototoro.core.db.migrations.Migration35To36(),
    org.skepsun.kototoro.core.db.migrations.Migration36To37(),
    Migration37To38(),
    Migration38To39(),
    Migration39To40(),
    Migration40To41(),
    Migration41To42(),
    Migration42To43(),
    Migration43To44(),
    Migration44To45(),
    Migration45To46(),
    Migration46To47(),
    Migration47To48(),
    Migration48To49(),
    org.skepsun.kototoro.core.db.migrations.Migration49To50(),
    org.skepsun.kototoro.core.db.migrations.Migration50To51(),
    org.skepsun.kototoro.core.db.migrations.Migration51To52(),
    org.skepsun.kototoro.core.db.migrations.Migration52To53(),
    org.skepsun.kototoro.core.db.migrations.Migration53To54(),
    org.skepsun.kototoro.core.db.migrations.Migration54To55(),
    org.skepsun.kototoro.core.db.migrations.Migration55To56(),
    org.skepsun.kototoro.core.db.migrations.Migration56To57(),
    org.skepsun.kototoro.core.db.migrations.Migration57To58(),
    org.skepsun.kototoro.core.db.migrations.Migration58To59(),
    org.skepsun.kototoro.core.db.migrations.Migration59To60(),
    Migration60To61(),
    Migration61To62(),
    Migration62To63(),
    Migration63To64(),
    Migration64To65(),
    Migration65To66(),
    Migration66To67(),
    Migration67To68(),
    Migration68To69(),
    Migration69To70(),
    Migration70To71(),
    Migration71To72(),
    Migration72To73(),
    Migration73To74(),
    Migration74To75(),
    Migration75To76(),
    Migration76To77(),
    Migration77To78(),
    Migration78To79(),
    Migration79To80(),
    Migration80To81(),
    Migration81To82(),
    Migration82To83(),
    org.skepsun.kototoro.core.db.migrations.Migration83To84(),
)

// App-only pieces (DatabasePrePopulateCallback, fallbackToDestructiveMigrationOnDowngrade policy) are
// intentionally left out of the spike; the framework driver is kept so the legacy
// `Migration.migrate(SupportSQLiteDatabase)` implementations keep working unchanged.
fun MangaDatabase(context: Context): MangaDatabase = Room
    .databaseBuilder<MangaDatabase>(context, context.getDatabasePath("kototoro-db").absolutePath)
    .addMigrations(*getDatabaseMigrations(context))
    .build()
