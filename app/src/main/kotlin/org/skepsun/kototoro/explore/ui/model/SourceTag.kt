package org.skepsun.kototoro.explore.ui.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.skepsun.kototoro.R

@get:StringRes
val SourceTag.titleRes: Int
    get() = when (this) {
        SourceTag.BUILTIN -> R.string.built_in_sources
        SourceTag.MIHON -> R.string.mihon_sources
        SourceTag.ANIYOMI -> R.string.aniyomi_sources
        SourceTag.LEGADO -> R.string.source_type_legado
        SourceTag.TVBOX -> R.string.source_type_tvbox
        SourceTag.IREADER -> R.string.source_type_ireader
        SourceTag.CLOUDSTREAM -> R.string.source_type_cloudstream
        SourceTag.LNREADER -> R.string.source_type_lnreader
        SourceTag.TSUNDOKU -> R.string.source_type_tsundoku
        SourceTag.PINNED -> R.string.source_pinned
    }

@get:DrawableRes
val SourceTag.iconRes: Int
    get() = when (this) {
        SourceTag.BUILTIN -> R.drawable.ic_source_builtin
        SourceTag.MIHON -> R.drawable.ic_source_mihon
        SourceTag.ANIYOMI -> R.drawable.ic_source_aniyomi
        SourceTag.LEGADO -> R.drawable.ic_source_legado
        SourceTag.TVBOX -> R.drawable.ic_source_tvbox
        SourceTag.IREADER -> R.drawable.ic_source_ireader
        SourceTag.CLOUDSTREAM -> R.drawable.ic_source_cloudstream
        SourceTag.LNREADER -> R.drawable.ic_source_lnreader
        SourceTag.TSUNDOKU -> R.drawable.ic_source_tsundoku
        SourceTag.PINNED -> R.drawable.ic_pin
    }
