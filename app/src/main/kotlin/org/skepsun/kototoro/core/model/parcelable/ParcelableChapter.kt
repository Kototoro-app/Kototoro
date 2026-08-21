package org.skepsun.kototoro.core.model.parcelable

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentChapter

@Parcelize
data class ParcelableChapter(
    val chapter: ContentChapter,
) : Parcelable {

    companion object : Parceler<ParcelableChapter> {

        override fun create(parcel: Parcel) = ParcelableChapter(
            ContentChapter(
                id = parcel.readLong(),
                title = parcel.readString(),
                number = parcel.readFloat(),
                volume = parcel.readInt(),
                url = parcel.readString().orEmpty(),
                scanlator = parcel.readString(),
                uploadDate = parcel.readLong(),
                branch = parcel.readString(),
                source = ContentSource(parcel.readString()),
            ),
        )

        override fun ParcelableChapter.write(parcel: Parcel, flags: Int) = with(chapter) {
            parcel.writeLong(id)
            parcel.writeString(title)
            parcel.writeFloat(number)
            parcel.writeInt(volume)
            parcel.writeString(url)
            parcel.writeString(scanlator)
            parcel.writeLong(uploadDate)
            parcel.writeString(branch)
            parcel.writeString(source.name)
        }
    }
}
