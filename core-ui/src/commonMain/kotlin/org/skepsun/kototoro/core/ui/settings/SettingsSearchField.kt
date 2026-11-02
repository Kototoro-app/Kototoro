package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvTextInputFocusEscape
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

/** Android's compact search field. Keep local IME selection while an asynchronous host echoes the query. */
@Composable
fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = uiString("search"),
    enabled: Boolean = true,
    autofocus: Boolean = false,
    inputModifier: Modifier = Modifier,
    height: Dp = 40.dp,
    surface: @Composable (Modifier, @Composable () -> Unit) -> Unit = { bounds, content ->
        Surface(bounds, shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant, content = content)
    },
) {
    val focusRequester = remember { FocusRequester() }
    var value by remember { mutableStateOf(TextFieldValue(query)) }
    val pendingEdits = remember { mutableListOf<String>() }
    LaunchedEffect(query) {
        val echoedIndex = pendingEdits.lastIndexOf(query)
        when {
            query == value.text -> pendingEdits.clear()
            echoedIndex >= 0 -> repeat(echoedIndex + 1) { pendingEdits.removeAt(0) }
            else -> {
                value = TextFieldValue(query)
                pendingEdits.clear()
            }
        }
    }
    LaunchedEffect(autofocus, enabled) { if (autofocus && enabled) focusRequester.requestFocus() }
    surface(modifier.height(height)) {
        BasicTextField(
            value = value,
            onValueChange = {
                val changed = it.text != value.text
                value = it
                if (changed) {
                    pendingEdits.add(it.text)
                    onQueryChange(it.text)
                }
            },
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = inputModifier.fillMaxSize().focusRequester(focusRequester).tvTextInputFocusEscape(),
            decorationBox = { inner ->
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ReaderPanelIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (value.text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                }
            },
        )
    }
}
