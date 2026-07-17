package org.skepsun.kototoro.settings.sources.replace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.replace.ReplaceRule
import org.skepsun.kototoro.core.replace.ReplaceRuleRepository
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReplaceRulesFragment : Fragment() {

    private lateinit var repo: ReplaceRuleRepository
    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val text = requireContext().contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.readText() ?: ""
                val count = repo.importFromJson(text)
                Toast.makeText(requireContext(), "导入 $count 条规则", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ReplaceRuleRepository(requireContext())
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ) = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            KototoroTheme {
                ReplaceRulesScreen(repo, importFileLauncher)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplaceRulesScreen(
    repo: ReplaceRuleRepository,
    importFileLauncher: androidx.activity.result.ActivityResultLauncher<String>,
) {
    var rules by remember { mutableStateOf<List<ReplaceRule>>(emptyList()) }
    var editingRule by remember { mutableStateOf<ReplaceRule?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<ReplaceRule?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch(Dispatchers.IO) {
            rules = repo.getAll()
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("替换规则") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.80f),
                ),
                actions = {
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/json"
                        }
                        importFileLauncher.launch("application/json")
                    }) {
                        Text("导入", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val json = repo.exportToJson()
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_TEXT, json)
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, "导出替换规则")
                            )
                        }
                    }) {
                        Text("导出", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = {
                        editingRule = ReplaceRule(id = System.currentTimeMillis())
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "添加")
                    }
                }
            )
        }
    ) { padding ->
        if (rules.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无替换规则", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        editingRule = ReplaceRule(id = System.currentTimeMillis())
                    }) {
                        Text("添加第一条规则")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rules, key = { it.id }) { rule ->
                    ReplaceRuleCard(
                        rule = rule,
                        onEdit = { editingRule = rule },
                        onToggle = {
                            scope.launch(Dispatchers.IO) {
                                val updated = rules.map {
                                    if (it.id == rule.id) it.copy(isEnabled = !it.isEnabled) else it
                                }
                                repo.saveAll(updated)
                                rules = updated
                            }
                        },
                        onDelete = { showDeleteConfirm = rule },
                    )
                }
            }
        }
    }

    if (editingRule != null) {
        ReplaceRuleEditDialog(
            rule = editingRule!!,
            onDismiss = { editingRule = null },
            onSave = { savedRule ->
                scope.launch(Dispatchers.IO) {
                    val updated = rules.toMutableList()
                    val idx = updated.indexOfFirst { it.id == savedRule.id }
                    if (idx >= 0) updated[idx] = savedRule
                    else updated.add(savedRule)
                    repo.saveAll(updated)
                    rules = updated
                }
                editingRule = null
            }
        )
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("删除规则") },
            text = { Text("确定删除「${showDeleteConfirm!!.name.ifBlank { showDeleteConfirm!!.pattern.take(30) }}」？") },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = showDeleteConfirm!!
                    scope.launch(Dispatchers.IO) {
                        val updated = rules.filter { it.id != toDelete.id }
                        repo.saveAll(updated)
                        rules = updated
                    }
                    showDeleteConfirm = null
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun ReplaceRuleCard(
    rule: ReplaceRule,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = rule.name.ifBlank { rule.pattern.take(40) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = rule.isEnabled, onCheckedChange = { onToggle() })
            }
            if (rule.pattern.isNotBlank()) {
                Text(
                    text = rule.pattern.take(80),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "→ ${rule.replacement.take(30).ifBlank { "(空)" }}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val scopeText = when (rule.scope) {
                    ReplaceRule.Scope.TITLE -> "标题"
                    ReplaceRule.Scope.CONTENT -> "正文"
                    ReplaceRule.Scope.BOTH -> "标题+正文"
                }
                Text(text = scopeText, style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplaceRuleEditDialog(
    rule: ReplaceRule,
    onDismiss: () -> Unit,
    onSave: (ReplaceRule) -> Unit,
) {
    var name by remember { mutableStateOf(rule.name) }
    var pattern by remember { mutableStateOf(rule.pattern) }
    var replacement by remember { mutableStateOf(rule.replacement) }
    var isRegex by remember { mutableStateOf(rule.isRegex) }
    var scopeContent by remember { mutableStateOf(rule.scopeContent) }
    var scopeTitle by remember { mutableStateOf(rule.scopeTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule.pattern.isBlank()) "添加规则" else "编辑规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("正则表达式") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text("替换为") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("正则", modifier = Modifier.align(Alignment.CenterVertically))
                    Switch(checked = isRegex, onCheckedChange = { isRegex = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("正文", modifier = Modifier.align(Alignment.CenterVertically))
                    Switch(checked = scopeContent, onCheckedChange = {
                        scopeContent = it
                        if (!it && !scopeTitle) scopeTitle = true
                    })
                    Spacer(Modifier.width(12.dp))
                    Text("标题", modifier = Modifier.align(Alignment.CenterVertically))
                    Switch(checked = scopeTitle, onCheckedChange = {
                        scopeTitle = it
                        if (!it && !scopeContent) scopeContent = true
                    })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pattern.isNotBlank()) {
                    onSave(
                        rule.copy(
                            name = name,
                            pattern = pattern,
                            replacement = replacement,
                            isRegex = isRegex,
                            scopeContent = scopeContent,
                            scopeTitle = scopeTitle,
                        )
                    )
                }
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
