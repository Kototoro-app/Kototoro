package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
        OutlinedTextField(address, { address = it }, label = { Text("仓库地址") },
            placeholder = { Text("https://example.com/index.pb") }, singleLine = true, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("repository-url"))
        Button({ controller.fetchRepository(address) }, enabled = enabled && address.isNotBlank(),
            modifier = Modifier.testTag("repository-add")) { Text("添加 / 读取仓库") }
        if (state.repositories.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 120.dp)) {
                items(state.repositories, key = { it.indexUrl }) { repo ->
                    TextButton({ address = repo.indexUrl; controller.fetchRepository(repo.indexUrl) }, enabled = enabled,
                        modifier = Modifier.testTag("repository:${repo.indexUrl}")) {
                        Text("${repo.name} · 刷新", maxLines = 1)
                    }
                }
            }
        }
        InstalledExtensions(controller, state, enabled)
        state.extensionCatalog?.let { catalog ->
            Text("${catalog.repository.name} · ${catalog.extensions.size} 个扩展")
            OutlinedTextField(query, { query = it }, label = { Text("查找扩展、语言或来源") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("extension-search"))
            val extensions = catalog.extensions.filter { extension ->
                query.isBlank() || extension.name.contains(query, true) || extension.packageName.contains(query, true) ||
                    extension.sources.any { it.name.contains(query, true) || it.language.contains(query, true) }
            }
            LazyColumn(Modifier.weight(1f).testTag("extension-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(extensions, key = { it.packageName }) { extension ->
                    val installed = state.installedExtensions[extension.packageName]
                    val anime = extension.packageName.contains(".animeextension.")
                    val compatible = extension.extensionLib.matches(
                        // Mihon 1.4-1.6, Tsundoku 1.4/1.6 (checked again on install), Aniyomi generations 12-16.
                        if (anime) Regex("1[2-6](?:\\.\\d+)*") else Regex("1\\.[456](?:\\.\\d+)*"),
                    )
                    val hasPackage = extension.resources.jarUrl.isNotBlank() || extension.resources.apkUrl.isNotBlank()
                    val ecosystem = when {
                        anime -> "Aniyomi"
                        extension.packageName.contains(".novelextension.") -> "Tsundoku"
                        else -> "Mihon"
                    }
                    val label = when {
                        !hasPackage -> "没有安装包"
                        !compatible -> "API 暂不支持"
                        installed != null && installed > extension.versionCode -> "本地版本较新"
                        installed == extension.versionCode -> "已安装"
                        installed != null -> "更新"
                        extension.resources.jarUrl.isBlank() -> "安装（转换 APK）"
                        else -> "安装"
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${extension.name} · ${extension.versionName} · $ecosystem", style = MaterialTheme.typography.subtitle1)
                            Text(extension.sources.joinToString { "${it.name} (${it.language})" }, maxLines = 2)
                            Button({ controller.installExtension(extension) }, enabled = enabled && hasPackage && compatible &&
                                (installed == null || installed < extension.versionCode),
                                modifier = Modifier.testTag("extension-install:${extension.packageName}")) { Text(label) }
                        }
                    }
                }
            }
        }
    }
}

/** Installed extensions and plugins: updates from the saved repositories, automatic updates, and removal. */
@Composable
private fun InstalledExtensions(controller: DesktopController, state: DesktopAppState, enabled: Boolean) {
    var confirm by remember { mutableStateOf<DesktopInstalledEntry?>(null) }
    val updates = state.extensionUpdates.associateBy { it.extension.packageName }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("已安装 · ${state.installedEntries.size}", style = MaterialTheme.typography.subtitle1)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(state.autoUpdateExtensions, { controller.setAutoUpdateExtensions(it) },
                modifier = Modifier.testTag("extension-auto-update"))
            Text("启动时自动更新")
        }
        OutlinedButton({ controller.checkExtensionUpdates() }, enabled = enabled,
            modifier = Modifier.testTag("extension-check-updates")) { Text("检查更新") }
        Button({ controller.applyUpdates() }, enabled = enabled && updates.isNotEmpty(),
            modifier = Modifier.testTag("extension-update-all")) { Text("全部更新（${updates.size}）") }
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).testTag("installed-list"),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(state.installedEntries, key = { "${it.kind}:${it.id}" }) { entry ->
            val update = updates[entry.id]
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.label, maxLines = 1)
                        Text("${DesktopSourceLabels.ecosystem(entry.ecosystem)} · ${entry.version} · ${entry.sources} 个来源" +
                            (update?.let { " · 可更新到 ${it.extension.versionName}" } ?: ""),
                            style = MaterialTheme.typography.caption, maxLines = 1)
                    }
                    if (update != null) Button({ controller.applyUpdates(listOf(update)) }, enabled = enabled,
                        modifier = Modifier.testTag("extension-update:${entry.id}")) { Text("更新") }
                    TextButton({ confirm = entry }, enabled = enabled,
                        modifier = Modifier.testTag("extension-uninstall:${entry.id}")) { Text("卸载") }
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