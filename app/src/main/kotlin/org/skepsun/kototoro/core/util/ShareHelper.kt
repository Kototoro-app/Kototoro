package org.skepsun.kototoro.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.appUrl
import org.skepsun.kototoro.parsers.model.Content
import java.io.File

private const val TYPE_TEXT = "text/plain"
private const val TYPE_IMAGE = "image/*"
private const val TYPE_CBZ = "application/x-cbz"

@Deprecated("")
class ShareHelper(private val context: Context) {

    fun shareContentLink(manga: Content) {
        val text = buildString {
            append(manga.title)
            append("\n \n")
            append(manga.publicUrl)
            append("\n \n")
            append(manga.appUrl)
        }
        ShareCompat.IntentBuilder(context)
            .setText(text)
            .setType(TYPE_TEXT)
            .setChooserTitle(context.getString(R.string.share_s, manga.title))
            .startChooser()
    }

    fun shareContentLinks(manga: Collection<Content>) {
        if (manga.isEmpty()) {
            return
        }
        if (manga.size == 1) {
            shareContentLink(manga.first())
            return
        }
        val text = manga.joinToString("\n \n") {
            "${it.title} - ${it.publicUrl}"
        }
        ShareCompat.IntentBuilder(context)
            .setText(text)
            .setType(TYPE_TEXT)
            .setChooserTitle(R.string.share)
            .startChooser()
    }

    fun shareCbz(files: Collection<File>) {
        if (files.isEmpty()) {
            return
        }
        val intentBuilder = ShareCompat.IntentBuilder(context)
            .setType(TYPE_CBZ)
        for (file in files) {
            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
            intentBuilder.addStream(uri)
        }
        files.singleOrNull()?.let {
            intentBuilder.setChooserTitle(context.getString(R.string.share_s, it.name))
        } ?: run {
            intentBuilder.setChooserTitle(R.string.share)
        }
        intentBuilder.startChooser()
    }

    fun shareImage(uri: Uri) {
        ShareCompat.IntentBuilder(context)
            .setStream(uri)
            .setType(context.contentResolver.getType(uri) ?: TYPE_IMAGE)
            .setChooserTitle(R.string.share_image)
            .startChooser()
    }

    fun getShareTextIntent(text: String): Intent = ShareCompat.IntentBuilder(context)
        .setText(text)
        .setType(TYPE_TEXT)
        .setChooserTitle(R.string.share)
        .createChooserIntent()
}
