package org.skepsun.kototoro.history.data

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaTagsEntity
import org.skepsun.kototoro.core.db.entity.TagEntity

class HistoryWithContent(
    @Embedded val history: HistoryEntity,
    @Relation(
        parentColumn = "manga_id",
        entityColumn = "manga_id"
    )
    val manga: MangaEntity,
    @Relation(
        parentColumn = "manga_id",
        entityColumn = "tag_id",
        associateBy = Junction(MangaTagsEntity::class)
    )
    val tags: List<TagEntity>,
)