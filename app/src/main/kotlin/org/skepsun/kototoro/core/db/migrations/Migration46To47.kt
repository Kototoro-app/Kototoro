package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration46To47 : Migration(46, 47) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
			UPDATE chapters
			SET source = 'LOCAL_VIDEO'
			WHERE source = 'LOCAL'
			  AND (
			    lower(url) LIKE '%.m3u8%'
			    OR lower(url) LIKE '%.mp4%'
			    OR lower(url) LIKE '%.mkv%'
			    OR lower(url) LIKE '%.webm%'
			    OR lower(url) LIKE '%.ts%'
			    OR lower(url) LIKE '%.avi%'
			    OR lower(url) LIKE '%.mov%'
			    OR lower(url) LIKE '%.flv%'
			    OR lower(url) LIKE '%.wmv%'
			    OR lower(url) LIKE '%/video/%'
			  )
            """.trimIndent(),
        )

        db.execSQL(
            """
			UPDATE manga
			SET source = 'LOCAL_VIDEO'
			WHERE source = 'LOCAL'
			  AND (
			    lower(url) LIKE '%.m3u8%'
			    OR lower(url) LIKE '%.mp4%'
			    OR lower(url) LIKE '%.mkv%'
			    OR lower(url) LIKE '%.webm%'
			    OR lower(url) LIKE '%.ts%'
			    OR lower(url) LIKE '%.avi%'
			    OR lower(url) LIKE '%.mov%'
			    OR lower(url) LIKE '%.flv%'
			    OR lower(url) LIKE '%.wmv%'
			    OR lower(public_url) LIKE '%.m3u8%'
			    OR lower(public_url) LIKE '%.mp4%'
			    OR lower(public_url) LIKE '%.mkv%'
			    OR lower(public_url) LIKE '%.webm%'
			    OR lower(public_url) LIKE '%.ts%'
			    OR lower(public_url) LIKE '%.avi%'
			    OR lower(public_url) LIKE '%.mov%'
			    OR lower(public_url) LIKE '%.flv%'
			    OR lower(public_url) LIKE '%.wmv%'
			    OR lower(url) LIKE '%/video/%'
			    OR lower(public_url) LIKE '%/video/%'
			    OR EXISTS (
			      SELECT 1
			      FROM chapters
			      WHERE chapters.manga_id = manga.manga_id
			        AND chapters.source = 'LOCAL_VIDEO'
			    )
			  )
            """.trimIndent(),
        )
    }
}
