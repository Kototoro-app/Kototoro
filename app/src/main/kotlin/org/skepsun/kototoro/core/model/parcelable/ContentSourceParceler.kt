package org.skepsun.kototoro.core.model.parcelable

import android.os.Parcel
import kotlinx.parcelize.Parceler
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentSource

class ContentSourceParceler : Parceler<ContentSource> {

    override fun create(parcel: Parcel): ContentSource = ContentSource(parcel.readString())

    override fun ContentSource.write(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
    }
}
