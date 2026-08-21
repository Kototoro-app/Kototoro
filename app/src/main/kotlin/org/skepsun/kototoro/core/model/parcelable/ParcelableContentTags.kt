package org.skepsun.kototoro.core.model.parcelable

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag

object ContentTagParceler : Parceler<ContentTag> {
    override fun create(parcel: Parcel) = ContentTag(
        title = requireNotNull(parcel.readString()),
        key = requireNotNull(parcel.readString()),
        source = ContentSource(parcel.readString()),
    )

    override fun ContentTag.write(parcel: Parcel, flags: Int) {
        parcel.writeString(title)
        parcel.writeString(key)
        parcel.writeString(source.name)
    }
}

@Parcelize
@TypeParceler<ContentTag, ContentTagParceler>
data class ParcelableContentTags(val tags: Set<ContentTag>) : Parcelable
