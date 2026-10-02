package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 添加parent_chapter_id字段到history表，用于支持EPUB内部章节的历史记录
 *
 * 对于EPUB内部章节：
 * - chapter_id: 内部章节ID（用于精确定位）
 * - parent_chapter_id: 父章节ID（用于文件定位）
 *
 * 对于普通章节：
 * - chapter_id: 章节ID
 * - parent_chapter_id: NULL或等于chapter_id
 */
class Migration29To30 : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 添加parent_chapter_id列，默认为NULL
        db.execSQL("ALTER TABLE history ADD COLUMN parent_chapter_id INTEGER DEFAULT NULL")
    }
}
