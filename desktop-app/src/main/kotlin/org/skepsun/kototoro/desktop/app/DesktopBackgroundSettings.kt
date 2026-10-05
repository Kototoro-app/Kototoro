package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.suggestions.domain.parseSuggestionTags

/** Android's suggestion and tracker settings screens, condensed for the Windows "more" page. */
@Composable
internal fun DesktopBackgroundSettings(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    val suggestions = state.suggestionSettings
    val tracker = state.trackerSettings
    Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().testTag("settings-suggestions")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("推荐", style = MaterialTheme.typography.titleMedium)
            SettingSwitch("启用推荐", "根据阅读历史和收藏的标签，每 6 小时从已安装来源生成一次", suggestions.enabled, enabled,
                "settings-suggestions-enabled") { controller.suggestionSettings(suggestions.copy(enabled = it)) }
            SettingSwitch("排除成人内容", null, suggestions.excludeNsfw, enabled && suggestions.enabled,
                "settings-suggestions-nsfw") { controller.suggestionSettings(suggestions.copy(excludeNsfw = it)) }
            TagListField("排除标签", suggestions.tagBlacklist, enabled && suggestions.enabled, "settings-suggestions-exclude-tags") {
                controller.suggestionSettings(suggestions.copy(tagBlacklist = it))
            }
            TagListField("偏好标签", suggestions.tagWhitelist, enabled && suggestions.enabled, "settings-suggestions-preferred-tags") {
                controller.suggestionSettings(suggestions.copy(tagWhitelist = it))
            }
            if (state.sources.isNotEmpty()) {
                Text("来源", style = MaterialTheme.typography.labelLarge)
                state.sources.forEach { source ->
                    val name = source.source.name
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(source.displayName, modifier = Modifier.weight(1f))
                        FilterChip(name in suggestions.preferredSources, enabled = enabled && suggestions.enabled,
                            onClick = { controller.suggestionSettings(suggestions.copy(
                                preferredSources = suggestions.preferredSources.toggle(name),
                                excludedSources = suggestions.excludedSources - name)) },
                            label = { Text("优先") }, modifier = Modifier.testTag("settings-suggestions-prefer:$name"))
                        FilterChip(name in suggestions.excludedSources, enabled = enabled && suggestions.enabled,
                            onClick = { controller.suggestionSettings(suggestions.copy(
                                excludedSources = suggestions.excludedSources.toggle(name),
                                preferredSources = suggestions.preferredSources - name)) },
                            label = { Text("排除") }, modifier = Modifier.testTag("settings-suggestions-exclude:$name"))
                    }
                }
            }
        }
    }
    Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().testTag("settings-tracker")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("更新检查", style = MaterialTheme.typography.titleMedium)
            SettingSwitch("检查新章节", "检查开启了更新追踪的收藏分类中的作品", tracker.enabled, enabled,
                "settings-tracker-enabled") { controller.trackerSettings(tracker.copy(enabled = it)) }
            Text("自动检查更新频率", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DesktopTrackerFrequencies.forEach { (value, label) ->
                    FilterChip(tracker.frequency == value, enabled = enabled && tracker.enabled,
                        onClick = { controller.trackerSettings(tracker.copy(frequency = value)) },
                        label = { Text(label) }, modifier = Modifier.testTag("settings-tracker-frequency:$value"))
                }
            }
        }
    }
}

private fun Set<String>.toggle(value: String) = if (value in this) this - value else this + value

@Composable
private fun SettingSwitch(title: String, summary: String?, checked: Boolean, enabled: Boolean, tag: String,
    onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Android edits tag lists as comma-separated text and parses them with the shared rule. */
@Composable
private fun TagListField(title: String, tags: Set<String>, enabled: Boolean, tag: String, onSave: (Set<String>) -> Unit) {
    var text by remember(tags) { mutableStateOf(tags.joinToString(", ")) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(text, { text = it }, label = { Text(title) }, singleLine = true, enabled = enabled,
            placeholder = { Text("用逗号分隔") }, modifier = Modifier.weight(1f).testTag(tag))
        TextButton({ onSave(parseSuggestionTags(text)) }, enabled = enabled && parseSuggestionTags(text) != tags,
            modifier = Modifier.testTag("$tag-save")) { Text("保存") }
    }
}
