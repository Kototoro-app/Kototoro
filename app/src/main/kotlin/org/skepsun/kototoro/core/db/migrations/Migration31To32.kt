package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 创建 json_sources 表，用于存储运行时导入的 JSON 解析器配置
 * 
 * 支持的 JSON 源类型：
 * - LEGADO: Legado 书源配置
 * - TVBOX: TVBox 站点配置
 */
class Migration31To32 : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 创建 json_sources 表
        db.execSQL(
            """
			CREATE TABLE IF NOT EXISTS json_sources (
				id TEXT PRIMARY KEY NOT NULL,
				name TEXT NOT NULL,
				type TEXT NOT NULL,
				config TEXT NOT NULL,
				enabled INTEGER NOT NULL DEFAULT 1,
				created_at INTEGER NOT NULL,
				updated_at INTEGER NOT NULL,
				last_used_at INTEGER NOT NULL DEFAULT 0,
				is_pinned INTEGER NOT NULL DEFAULT 0
			)
            """.trimIndent()
        )

        // 创建索引以优化查询性能
        db.execSQL(
            """
			CREATE INDEX IF NOT EXISTS index_json_sources_enabled 
			ON json_sources(enabled)
            """.trimIndent()
        )

        db.execSQL(
            """
			CREATE INDEX IF NOT EXISTS index_json_sources_type 
			ON json_sources(type)
            """.trimIndent()
        )
        
        // 添加复合索引以优化按类型和启用状态的查询
        db.execSQL(
            """
			CREATE INDEX IF NOT EXISTS index_json_sources_enabled_type 
			ON json_sources(enabled, type)
            """.trimIndent()
        )
        
        // 添加索引以优化最近使用的查询
        db.execSQL(
            """
			CREATE INDEX IF NOT EXISTS index_json_sources_last_used 
			ON json_sources(last_used_at DESC)
            """.trimIndent()
        )
        
        // 添加索引以优化按名称排序的查询
        db.execSQL(
            """
			CREATE INDEX IF NOT EXISTS index_json_sources_name 
			ON json_sources(name)
            """.trimIndent()
        )
    }
}
