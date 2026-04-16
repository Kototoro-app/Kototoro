package org.skepsun.kototoro.discover.bangumidata.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.discover.bangumidata.domain.BangumiDataRoot
import org.skepsun.kototoro.discover.bangumidata.domain.OfficialSiteDetails
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BangumiDataRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val jsonParser = Json { ignoreUnknownKeys = true }
    private var cachedData: BangumiDataRoot? = null
    
    private val bangumiIndex = mutableMapOf<String, List<OfficialSiteDetails>>()
    
    suspend fun getOfficialSites(bangumiId: String): List<OfficialSiteDetails> {
        val root = requireData()
        if (bangumiIndex.isEmpty()) {
            buildIndex(root)
        }
        return bangumiIndex[bangumiId] ?: emptyList()
    }
    
    private suspend fun requireData(): BangumiDataRoot {
        return cachedData ?: withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "bangumi-data.json")
            val content = if (file.exists() && file.length() > 0) {
                file.readText()
            } else {
                context.assets.open("bangumi-data.json").bufferedReader().use { it.readText() }
            }
            jsonParser.decodeFromString<BangumiDataRoot>(content).also { 
                cachedData = it 
            }
        }
    }
    
    private suspend fun buildIndex(root: BangumiDataRoot) = withContext(Dispatchers.Default) {
        val meta = root.siteMeta
        for (item in root.items) {
            val bangumiLink = item.sites.firstOrNull { it.site == "bangumi" } ?: continue
            val offSites = item.sites.filter { it.site != "bangumi" }.mapNotNull { link ->
                val siteMeta = meta[link.site] ?: return@mapNotNull null
                OfficialSiteDetails(
                    title = siteMeta.title,
                    url = siteMeta.urlTemplate.replace("{{id}}", link.id),
                    type = siteMeta.type,
                    regions = siteMeta.regions ?: emptyList()
                )
            }
            if (offSites.isNotEmpty()) {
                bangumiIndex[bangumiLink.id] = offSites
            }
        }
    }
    
    suspend fun invalidateCache() = withContext(Dispatchers.Default) {
        cachedData = null
        bangumiIndex.clear()
    }
}
