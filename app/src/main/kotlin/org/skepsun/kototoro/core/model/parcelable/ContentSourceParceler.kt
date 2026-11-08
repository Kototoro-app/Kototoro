package org.skepsun.kototoro.core.model.parcelable

import android.os.Parcel
import kotlinx.parcelize.Parceler
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.util.ext.readSerializableCompat
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class ContentSourceParceler : Parceler<ContentSource> {

    override fun create(parcel: Parcel): ContentSource {
        val name = parcel.readString()
        val fallbackType = parcel.readSerializableCompat<ContentType>()
        return ContentSource(name, fallbackType)
    }

    override fun ContentSource.write(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
        parcel.writeSerializable(getContentType())
    }
}
