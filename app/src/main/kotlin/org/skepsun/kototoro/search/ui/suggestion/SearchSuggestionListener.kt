package org.skepsun.kototoro.search.ui.suggestion

import android.text.TextWatcher
import android.widget.TextView
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.search.domain.SearchKind

interface SearchSuggestionListener : TextWatcher, TextView.OnEditorActionListener {

    fun onContentClick(manga: Content)

    fun onQueryClick(query: String, kind: SearchKind, submit: Boolean)

    fun onSourceToggle(source: ContentSource, isEnabled: Boolean)

    fun onSourceClick(source: ContentSource)

    fun onTagClick(tag: ContentTag)
}
