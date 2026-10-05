package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopExtensionsPanel(controller: DesktopController, closing: Boolean) {
    val state by controller.state.collectAsState()
    var address by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val enabled = !state.busy && !closing
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("扩展仓库", style = MaterialTheme.typography.h6)
        Text("添加仓库后选择扩展安装。支持 index.pb、新版 index.json 与 Aniyomi 等仓库的 index.min.json；只发布 APK 的扩展会在本机转换为 JVM 类文件后安装。")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(address, { address = it }, label = { Text("仓库地址") },
                placeholder = { Text("https://example.com/index.pb") }, singleLine = true, enabled = enabled,
                modifier = Modifier.weight(1f).testTag("repository-url"))
            Button({ controller.fetchRepository(address) }, enabled = enabled && address.isNotBlank(),
                modifier = Modifier.testTag("repository-add")) { Text("添加 / 读取仓库") }
        }
        if (state.repositories.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.repositories, key = { it.indexUrl }) { repo ->
                    TextButton({ address = repo.indexUrl; controller.fetchRepository(repo.indexUrl) }, enabled = enabled,
                        modifier = Modifier.testTag("repository:${repo.indexUrl}")) {
                        Text("${repo.name} · 刷新", maxLines = 1)
                    }
                }
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ExtensionCatalog(controller, state, query, { query = it }, enabled, Modifier.weight(1f).fillMaxHeight())
            InstalledExtensions(controller, state, enabled, Modifier.width(360.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun ExtensionCatalog(
    controller: DesktopController,
    state: DesktopAppState,
    query: String,
    onQueryChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier,
) {
    val catalog = state.extensionCatalog
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(catalog?.let { "${it.repository.name} · ${it.extensions.size} 个扩展" } ?: "仓库扩展",
            style = MaterialTheme.typography.subtitle1)
        OutlinedTextField(query, onQueryChange, label = { Text("查找扩展、语言或来源") }, singleLine = true,
            enabled = catalog != null, modifier = Modifier.fillMaxWidth().testTag("extension-search"))
        val extensions = catalog?.extensions.orEmpty().filter { extension ->
            query.isBlank() || extension.name.contains(query, true) || extension.packageName.contains(query, true) ||
                extension.sources.any { it.name.contains(query, true) || it.language.contains(query, true) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("extension-list"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(extensions, key = { it.packageName }) { extension ->
                val parserPlugin = requireNotNull(catalog).isParserPlugin(extension)
                val cloudstreamPlugin = catalog.isCloudstreamPlugin(extension)
                val installed = state.installedExtensions[extension.packageName]
                val parserInstalled = state.installedEntries.any {
                    it.kind == DesktopInstalledKind.PARSER && it.id == extension.packageName
                }
                val anime = !parserPlugin && extension.packageName.contains(".animeextension.")
                val compatible = parserPlugin || cloudstreamPlugin || extension.extensionLib.matches(
                    // Mihon 1.4-1.6, Tsundoku 1.4/1.6 (checked again on install), Aniyomi generations 12-16.
                    if (anime) Regex("1[2-6](?:\\.\\d+)*") else Regex("1\\.[456](?:\\.\\d+)*"),
                )
                val hasPackage = extension.resources.jarUrl.isNotBlank() || extension.resources.apkUrl.isNotBlank()
                val ecosystem = when {
                    cloudstreamPlugin -> "Cloudstream"
                    parserPlugin -> "解析器插件"
                    anime -> "Aniyomi"
                    extension.packageName.contains(".novelextension.") -> "Tsundoku"
                    else -> "Mihon"
                }
                val label = when {
                    !hasPackage -> "没有安装包"
                    !compatible -> "API 暂不支持"
                    parserInstalled -> "已安装"
                    installed != null && installed > extension.versionCode -> "本地版本较新"
                    installed == extension.versionCode -> "已安装"
                    installed != null -> "更新"
                    extension.resources.jarUrl.isBlank() && !parserPlugin && !cloudstreamPlugin -> "安装（转换 APK）"
                    else -> "安装"
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${extension.name} · ${extension.versionName} · $ecosystem",
                            style = MaterialTheme.typography.subtitle1)
                        Text(extension.sources.takeIf { it.isNotEmpty() }
                            ?.joinToString { "${it.name} (${it.language})" }
                            ?: "安装后读取插件内置来源", maxLines = 2)
                        Button({ controller.installExtension(extension) }, enabled = enabled && hasPackage && compatible &&
                            !parserInstalled && (installed == null || installed < extension.versionCode),
                            modifier = Modifier.testTag("extension-install:${extension.packageName}")) { Text(label) }
                    }
                }
            }
        }
    }
}

/** Installed extensions and plugins: updates from the saved repositories, automatic updates, and removal. */
@Composable
private fun InstalledExtensions(
    controller: DesktopController,
    state: DesktopAppState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf<DesktopInstalledEntry?>(null) }
    val updates = state.extensionUpdates.associateBy { it.extension.packageName }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("已安装 · ${state.installedEntries.size}", style = MaterialTheme.typography.subtitle1)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(state.autoUpdateExtensions, { controller.setAutoUpdateExtensions(it) },
                modifier = Modifier.testTag("extension-auto-update"))
            Text("启动时自动更新")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ controller.checkExtensionUpdates() }, enabled = enabled,
                modifier = Modifier.weight(1f).testTag("extension-check-updates")) { Text("检查更新") }
            Button({ controller.applyUpdates() }, enabled = enabled && updates.isNotEmpty(),
                modifier = Modifier.weight(1f).testTag("extension-update-all")) { Text("全部更新（${updates.size}）") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("installed-list"),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.installedEntries, key = { "${it.kind}:${it.id}" }) { entry ->
                val update = updates[entry.id]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(entry.label, maxLines = 1)
                        Text("${DesktopSourceLabels.ecosystem(entry.ecosystem)} · ${entry.version} · ${entry.sources} 个来源" +
                            (update?.let { " · 可更新到 ${it.extension.versionName}" } ?: ""),
                            style = MaterialTheme.typography.caption, maxLines = 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (update != null) Button({ controller.applyUpdates(listOf(update)) }, enabled = enabled,
                                modifier = Modifier.testTag("extension-update:${entry.id}")) { Text("更新") }
                            TextButton({ confirm = entry }, enabled = enabled,
                                modifier = Modifier.testTag("extension-uninstall:${entry.id}")) { Text("卸载") }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { entry ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("卸载 ${entry.label}？") },
            text = { Text("移除它的 ${entry.sources} 个来源和安装文件。收藏、历史与下载保留，重新安装后可继续使用。") },
            confirmButton = { Button({ confirm = null; controller.uninstall(entry) },
                modifier = Modifier.testTag("extension-uninstall-confirm")) { Text("卸载") } },
            dismissButton = { TextButton({ confirm = null }) { Text("取消") } })
    }
}
