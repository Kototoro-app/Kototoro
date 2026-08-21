package org.skepsun.kototoro.core.exceptions

import android.net.Uri

class NonFileUriException(
    val uri: Uri,
) : IllegalArgumentException("Cannot resolve file name of \"$uri\"")
