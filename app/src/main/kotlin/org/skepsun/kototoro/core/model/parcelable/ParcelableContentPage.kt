package org.skepsun.kototoro.core.model.parcelable

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentExternalTrack
import org.skepsun.kototoro.parsers.model.ContentPage

object ContentPageParceler : Parceler<ContentPage> {
    override fun create(parcel: Parcel) = ContentPage(
        id = parcel.readLong(),
        url = requireNotNull(parcel.readString()),
        preview = parcel.readString(),
        headers = parcel.readStringMap(),
        externalSubtitleTracks = parcel.readExternalTracks(),
        playbackLabel = parcel.readString(),
        playbackQuality = parcel.readInt().takeIf { it >= 0 },
        source = ContentSource(parcel.readString()),
    )

    override fun ContentPage.write(parcel: Parcel, flags: Int) {
        parcel.writeLong(id)
        parcel.writeString(url)
        parcel.writeString(preview)
        parcel.writeStringMap(headers)
        parcel.writeExternalTracks(externalSubtitleTracks)
        parcel.writeString(playbackLabel)
        parcel.writeInt(playbackQuality ?: -1)
        parcel.writeString(source.name)
    }

    private fun Parcel.readStringMap(): Map<String, String>? {
        val size = readInt()
        if (size < 0) return null
        return buildMap(size) {
            repeat(size) {
                val key = readString() ?: return@repeat
                val value = readString().orEmpty()
                put(key, value)
            }
        }
    }

    private fun Parcel.writeStringMap(map: Map<String, String>?) {
        if (map == null) {
            writeInt(-1)
            return
        }
        writeInt(map.size)
        map.forEach { (key, value) ->
            writeString(key)
            writeString(value)
        }
    }

    private fun Parcel.readExternalTracks(): List<ContentExternalTrack> {
        val size = readInt()
        if (size <= 0) return emptyList()
        return buildList(size) {
            repeat(size) {
                val url = readString() ?: return@repeat
                val lang = readString().orEmpty()
                val headers = readStringMap()
                add(ContentExternalTrack(url = url, lang = lang, headers = headers))
            }
        }
    }

    private fun Parcel.writeExternalTracks(tracks: List<ContentExternalTrack>) {
        writeInt(tracks.size)
        tracks.forEach { track ->
            writeString(track.url)
            writeString(track.lang)
            writeStringMap(track.headers)
        }
    }
}

@Parcelize
@TypeParceler<ContentPage, ContentPageParceler>
class ParcelableContentPage(val page: ContentPage) : Parcelable
