package com.lagradost.cloudstream3.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.utils.DataStoreHelper

/**
 * The part of Cloudstream's home screen model plugins reach for (sync plugins read the resume list, Ultima its
 * pages). Kototoro has no Cloudstream home screen, so it stays empty.
 */
class HomeViewModel : ViewModel() {
	val page: LiveData<Map<String, ExpandableHomepageList>> = MutableLiveData(emptyMap())
	val preview: LiveData<Any?> = MutableLiveData(null)
	val randomItems: LiveData<List<SearchResponse>?> = MutableLiveData(null)
	var repo: APIRepository? = null

	fun loadMoreHomeScrollResponses() = Unit

	data class ExpandableHomepageList(
		var list: HomePageList,
		var currentPage: Int,
		var hasNext: Boolean,
	)

	companion object {
		suspend fun getResumeWatching(): List<DataStoreHelper.ResumeWatchingResult>? = emptyList()
	}
}
