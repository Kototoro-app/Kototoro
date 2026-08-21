package org.skepsun.kototoro.details.domain

import org.skepsun.kototoro.core.util.LocaleStringComparator
import org.skepsun.kototoro.details.ui.model.ContentBranch

class BranchComparator : Comparator<ContentBranch> {

    private val delegate = LocaleStringComparator()

    override fun compare(o1: ContentBranch, o2: ContentBranch): Int = delegate.compare(o1.name, o2.name)
}
