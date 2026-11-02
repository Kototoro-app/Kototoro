package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.adaptive.tvTakeInitialFocus

internal data class NovelTtsVoiceDialogState(
    val title: String,
    val entries: List<String>,
    val selectedIndex: Int,
    val onSelected: (Int) -> Unit,
    val onDismiss: () -> Unit,
    val onManage: (() -> Unit)? = null,
)

private val RowShape = RoundedCornerShape(10.dp)

@Composable
internal fun NovelTtsVoiceDialog(state: NovelTtsVoiceDialogState) {
    AlertDialog(
        onDismissRequest = state.onDismiss,
        title = { Text(state.title) },
        text = {
            // 电视上对话框是新窗口，没人持有焦点：不显式点名，第一次方向键落到哪全靠几何搜索。
            // 列表行还要自己画焦点环，否则遥控器上"选中项"完全看不出来。
            LazyColumn(modifier = Modifier.fillMaxWidth().tvTakeInitialFocus()) {
                itemsIndexed(state.entries) { index, entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RowShape)
                            .tvFocusable(shape = RowShape, addFocusTarget = false)
                            .clickable { state.onSelected(index) }
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                    ) {
                        // 行本身已经处理点击，单选项不再单独接焦点，避免每行两个焦点停靠点。
                        RadioButton(
                            selected = index == state.selectedIndex,
                            onClick = null,
                        )
                        Text(entry)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            state.onManage?.let { onManage ->
                TvTextButton(onClick = onManage) {
                    Text(stringResource(R.string.tts_legado_manage_sources))
                }
            }
        },
    )
}
