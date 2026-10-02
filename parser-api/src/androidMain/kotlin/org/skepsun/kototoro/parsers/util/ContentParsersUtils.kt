@file:JvmName("ContentParsersUtils")

package org.skepsun.kototoro.parsers.util

import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import kotlin.contracts.contract

public fun ContentListFilter?.isNullOrEmpty(): Boolean {
	contract {
		returns(false) implies (this@isNullOrEmpty != null)
	}
	return this == null || this.isEmpty()
}

public fun Collection<ContentChapter>.findById(chapterId: Long): ContentChapter? = find { x ->
	x.id == chapterId
}
