package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** One add flow, with format choices matching Android's repository kinds and local-file import. */
@Composable
internal fun DesktopExtensionImportDialog(
    enabled: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onRepository: (String) -> Unit,
    onSource: (String, String) -> Unit,
    onFile: (Path) -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var sourceConfig by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("extension-add-dialog"),
        title = { Text(desktopText("extensions.add")) },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(desktopText("extensions.addHint"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!enabled) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("extension-add-error")) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!sourceConfig, { sourceConfig = false },
                        { Text(desktopText("extensions.repositoryType")) }, enabled = enabled,
                        modifier = Modifier.testTag("extension-add-repository"))
                    FilterChip(sourceConfig, { sourceConfig = true }, { Text("TVBox / CMS") }, enabled = enabled,
                        modifier = Modifier.testTag("extension-add-tvbox"))
                }
                OutlinedTextField(address, { address = it }, singleLine = true, enabled = enabled,
                    label = { Text(desktopText("extensions.repositoryAddress")) },
                    placeholder = { Text(if (sourceConfig) desktopText("jsonSources.addressHint")
                        else "https://example.org/extensions/index.min.json") },
                    modifier = Modifier.fillMaxWidth().testTag("repository-url"))
                if (sourceConfig) {
                    OutlinedTextField(name, { name = it }, singleLine = true, enabled = enabled,
                        label = { Text(desktopText("jsonSources.name")) },
                        modifier = Modifier.fillMaxWidth().testTag("json-source-name"))
                }
                HorizontalDivider()
                Text(desktopText("extensions.localFileHint"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({
                    val chooser = JFileChooser().apply {
                        fileFilter = FileNameExtensionFilter(desktopText("extensions.localFileFilter"),
                            "jar", "apk", "cs3", "json")
                    }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        onFile(chooser.selectedFile.toPath())
                    }
                }, enabled = enabled, modifier = Modifier.testTag("extension-import-file")) {
                    Text(AndroidStrings["import_file"])
                }
            }
        },
        confirmButton = {
            Button({ if (sourceConfig) onSource(address.trim(), name.trim()) else onRepository(address.trim()) },
                enabled = enabled && address.isNotBlank(), modifier = Modifier.testTag("repository-add")) {
                Text(AndroidStrings["add"])
            }
        },
        dismissButton = {
            TextButton(onDismiss, modifier = Modifier.testTag("extension-add-cancel")) {
                Text(desktopText("common.cancel"))
            }
        },
    )
}
