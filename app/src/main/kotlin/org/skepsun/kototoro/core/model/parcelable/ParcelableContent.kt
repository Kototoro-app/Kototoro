package org.skepsun.kototoro.core.model.parcelable

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.util.ext.readParcelableCompat
import org.skepsun.kototoro.core.util.ext.readSerializableCompat
import org.skepsun.kototoro.core.util.ext.readStringSet
import org.skepsun.kototoro.core.util.ext.writeStringSet
import org.skepsun.kototoro.parsers.model.Content

@Parcelize
data class ParcelableContent(
    val manga: Content,
    private val withDescription: Boolean = true,
    private val withChapters: Boolean = false,
) : Parcelable {

    companion object : Parceler<ParcelableContent> {

        override fun ParcelableContent.write(parcel: Parcel, flags: Int): Unit = with(manga) {
            parcel.writeLong(id)
            parcel.writeString(title)
            parcel.writeStringSet(altTitles)
            parcel.writeString(url)
            parcel.writeString(publicUrl)
            parcel.writeFloat(rating)
            parcel.writeSerializable(contentRating)
            parcel.writeString(coverUrl)
            parcel.writeString(largeCoverUrl)
            parcel.writeString(description.takeIf { withDescription })
            parcel.writeParcelable(ParcelableContentTags(tags), flags)
            parcel.writeSerializable(state)
            parcel.writeStringSet(authors)
            parcel.writeString(source.name)
            // Write chapters if requested
            val chaptersToWrite = if (withChapters) chapters else null
            parcel.writeInt(chaptersToWrite?.size ?: -1)
            chaptersToWrite?.forEach { chapter ->
                parcel.writeLong(chapter.id)
                parcel.writeString(chapter.title)
                parcel.writeFloat(chapter.number)
                parcel.writeInt(chapter.volume)
                parcel.writeString(chapter.url)
                parcel.writeString(chapter.scanlator)
                parcel.writeLong(chapter.uploadDate)
                parcel.writeString(chapter.branch)
            }
        }

        override fun create(parcel: Parcel): ParcelableContent {
            val id = parcel.readLong()
            val title = requireNotNull(parcel.readString())
            val altTitles = parcel.readStringSet()
            val url = requireNotNull(parcel.readString())
            val publicUrl = requireNotNull(parcel.readString())
            val rating = parcel.readFloat()
            val contentRating = parcel.readSerializableCompat<org.skepsun.kototoro.parsers.model.ContentRating>()
            val coverUrl = parcel.readString()
            val largeCoverUrl = parcel.readString()
            val description = parcel.readString()
            val tags = requireNotNull(parcel.readParcelableCompat<ParcelableContentTags>()).tags
            val state = parcel.readSerializableCompat<org.skepsun.kototoro.parsers.model.ContentState>()
            val authors = parcel.readStringSet()
            val sourceName = requireNotNull(parcel.readString())
            
            // Read chapters if present
            val chaptersSize = parcel.readInt()
            val chapters = if (chaptersSize >= 0) {
                List(chaptersSize) {
                    org.skepsun.kototoro.parsers.model.ContentChapter(
                        id = parcel.readLong(),
                        title = parcel.readString(),
                        number = parcel.readFloat(),
                        volume = parcel.readInt(),
                        url = requireNotNull(parcel.readString()),
                        scanlator = parcel.readString(),
                        uploadDate = parcel.readLong(),
                        branch = parcel.readString(),
                        source = ContentSource(sourceName),
                    )
                }
            } else {
                null
            }
            
            return ParcelableContent(
                Content(
                    id = id,
                    title = title,
                    altTitles = altTitles,
                    url = url,
                    publicUrl = publicUrl,
                    rating = rating,
                    contentRating = contentRating,
                    coverUrl = coverUrl,
                    largeCoverUrl = largeCoverUrl,
                    description = description,
                    tags = tags,
                    state = state,
                    authors = authors,
                    chapters = chapters,
                    source = ContentSource(sourceName),
                ),
                withDescription = true,
                withChapters = chapters != null,
            )
        }
    }
}
