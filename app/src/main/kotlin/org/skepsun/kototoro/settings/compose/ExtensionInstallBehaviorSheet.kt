package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.compose.KototoroSheetSurface
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.settings.ExtensionInstallBehaviorContent
import org.skepsun.kototoro.core.ui.settings.ExtensionInstallBehaviorUiItem
import org.skepsun.kototoro.core.ui.settings.PreferenceChoice
import org.skepsun.kototoro.extensions.install.ExtensionInstallPolicy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionInstallBehaviorSheet(
    behaviors: List<ExtensionInstallBehaviorItem>,
    policyOptions: List<SettingsChoiceOption<ExtensionInstallPolicy>>,
    onPolicyChange: (String, ExtensionInstallPolicy) -> Unit,
    onBatchSetPolicy: (ExtensionInstallPolicy) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = null,
        shape = RoundedCornerShape(0.dp),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        modifier = modifier,
    ) {
        KototoroSheetSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            ExtensionInstallBehaviorContent(
                behaviors = behaviors.map {
                    ExtensionInstallBehaviorUiItem(it.type, it.title, it.policy.name, painterResource(it.iconRes))
                },
                policyOptions = policyOptions.map { PreferenceChoice(it.value.name, it.label) },
                onPolicyChange = { type, policy -> onPolicyChange(type, ExtensionInstallPolicy.valueOf(policy)) },
                onBatchSetPolicy = { onBatchSetPolicy(ExtensionInstallPolicy.valueOf(it)) },
                modifier = Modifier.padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 24.dp),
                topContent = { SheetDragHandle(Modifier.align(Alignment.CenterHorizontally)) },
                renderDialog = { AndroidPreferenceDialog(it) },
            )
        }
    }
}
