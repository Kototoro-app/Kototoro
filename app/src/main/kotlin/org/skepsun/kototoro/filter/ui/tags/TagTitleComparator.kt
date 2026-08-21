package org.skepsun.kototoro.filter.ui.tags

import org.skepsun.kototoro.parsers.model.ContentTag
import java.text.Collator
import java.util.Locale

class TagTitleComparator(lc: String?) : Comparator<ContentTag> {

    private val collator = lc?.let { Collator.getInstance(Locale(it)) }

    override fun compare(o1: ContentTag, o2: ContentTag): Int {
        val t1 = o1.title.lowercase()
        val t2 = o2.title.lowercase()
        return collator?.compare(t1, t2) ?: compareValues(t1, t2)
    }
}
