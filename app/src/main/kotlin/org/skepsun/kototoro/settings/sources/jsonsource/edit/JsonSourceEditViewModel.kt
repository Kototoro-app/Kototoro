package org.skepsun.kototoro.settings.sources.jsonsource.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceType
import org.skepsun.kototoro.core.jsonsource.JsonSourceImportMetadata
import org.skepsun.kototoro.core.jsonsource.JsonSourceManager
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import javax.inject.Inject

@HiltViewModel
class JsonSourceEditViewModel @Inject constructor(
    private val jsonSourceManager: JsonSourceManager,
    private val json: Json,
) : ViewModel() {

    private var currentSourceId: String? = null
    private var currentEntity: JsonSourceEntity? = null

    private val _source = MutableStateFlow<SourceEditData?>(null)
    val source: StateFlow<SourceEditData?> = _source.asStateFlow()

    private val _saveResult = MutableStateFlow<SaveResult?>(null)
    val saveResult: StateFlow<SaveResult?> = _saveResult.asStateFlow()

    fun loadSource(sourceId: String) {
        currentSourceId = sourceId
        viewModelScope.launch(Dispatchers.IO) {
            val entity = jsonSourceManager.getById(sourceId)
            currentEntity = entity

            if (entity != null) {
                try {
                    val legadoSource = json.decodeFromString<LegadoBookSource>(entity.config)
                    _source.value = SourceEditData(
                        name = legadoSource.bookSourceName,
                        url = legadoSource.bookSourceUrl,
                        group = legadoSource.bookSourceGroup,
                        searchUrl = legadoSource.searchUrl,
                        exploreUrl = legadoSource.exploreUrl,
                        enabled = entity.enabled
                    )
                } catch (e: Exception) {
                    _saveResult.value = SaveResult.Error("Failed to parse source: ${e.message}")
                }
            }
        }
    }

    fun saveSource(data: SourceEditData) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val timestamp = System.currentTimeMillis()
                val entity = currentEntity

                if (entity == null) {
                    // New source
                    val sourceId = jsonSourceManager.generateSourceId(data.url, JsonSourceType.LEGADO)
                    val newLegadoSource = LegadoBookSource(
                        bookSourceName = data.name,
                        bookSourceUrl = data.url,
                        bookSourceGroup = data.group,
                        searchUrl = data.searchUrl,
                        exploreUrl = data.exploreUrl,
                        enabled = data.enabled
                    )
                    val configJson = json.encodeToString(newLegadoSource)
                    val newEntity = JsonSourceEntity(
                        id = sourceId,
                        name = data.name,
                        type = JsonSourceType.LEGADO,
                        config = configJson,
                        enabled = data.enabled,
                        createdAt = timestamp,
                        updatedAt = timestamp,
                        lastUsedAt = 0,
                        isPinned = false,
                        iconUrl = deriveFaviconUrl(data.url),
                    )
                    jsonSourceManager.insertSource(newEntity)
                } else {
                    // Existing source
                    val existingSource = try {
                        json.decodeFromString<LegadoBookSource>(entity.config)
                    } catch (e: Exception) {
                        LegadoBookSource(
                            bookSourceName = data.name,
                            bookSourceUrl = data.url
                        )
                    }

                    val updatedSource = existingSource.copy(
                        bookSourceName = data.name,
                        bookSourceUrl = data.url,
                        bookSourceGroup = data.group,
                        searchUrl = data.searchUrl,
                        exploreUrl = data.exploreUrl,
                        enabled = data.enabled
                    )

                    val updatedConfig = JsonSourceImportMetadata.copyMetadata(
                        fromConfig = entity.config,
                        toConfig = json.encodeToString(updatedSource),
                    )
                    val updatedEntity = entity.copy(
                        name = data.name,
                        config = updatedConfig,
                        enabled = data.enabled,
                        updatedAt = timestamp,
                        iconUrl = deriveFaviconUrl(data.url),
                    )

                    jsonSourceManager.updateSource(updatedEntity)
                }

                _saveResult.value = SaveResult.Success
            } catch (e: Exception) {
                _saveResult.value = SaveResult.Error("Failed to save: ${e.message}")
            }
        }
    }

    private fun deriveFaviconUrl(siteUrl: String?): String? {
        val trimmed = siteUrl?.trim().orEmpty()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return runCatching {
            val uri = java.net.URI(trimmed)
            val scheme = uri.scheme?.takeIf { it.equals("http", true) || it.equals("https", true) } ?: return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
            "$scheme://$host$port/favicon.ico"
        }.getOrNull()
    }
}

data class SourceEditData(
    val name: String,
    val url: String,
    val group: String?,
    val searchUrl: String?,
    val exploreUrl: String?,
    val enabled: Boolean
)

sealed class SaveResult {
    object Success : SaveResult()
    data class Error(val message: String) : SaveResult()
}
