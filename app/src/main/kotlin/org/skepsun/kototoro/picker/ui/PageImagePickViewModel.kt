package org.skepsun.kototoro.picker.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.reader.ui.PageSaveHelper
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PageImagePickViewModel @Inject constructor() : BaseViewModel() {

    val onFileReady = MutableEventFlow<File>()

    fun savePageToTempFile(pageSaveHelper: PageSaveHelper, task: PageSaveHelper.Task) {
        launchLoadingJob(Dispatchers.Default) {
            val file = pageSaveHelper.saveToTempFile(task)
            onFileReady.call(file)
        }
    }
}
