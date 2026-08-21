package org.skepsun.kototoro.picker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.parsers.model.Content

class PageImagePickContract : ActivityResultContract<Content?, Uri?>() {

    override fun createIntent(context: Context, input: Content?): Intent =
        Intent(context, PageImagePickActivity::class.java)
            .putExtra(AppRouter.KEY_MANGA, input?.let { ParcelableContent(it) })

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}
