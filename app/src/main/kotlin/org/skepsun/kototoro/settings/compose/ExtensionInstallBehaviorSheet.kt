package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.KototoroSheetSurface
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
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
    val distinctPolicies = remember(behaviors) { behaviors.map { it.policy }.distinct() }
    val uniformPolicy = if (distinctPolicies.size == 1) distinctPolicies.first() else null

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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SheetDragHandle(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.extension_install_behavior),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.extension_install_behavior_sheet_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.extension_install_behavior_batch_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = settingsSectionLabelColor(),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        policyOptions.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = uniformPolicy == option.value,
                                onClick = { onBatchSetPolicy(option.value) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = policyOptions.size,
                                ),
                                label = {
                                    Text(
                                        text = option.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
                SettingsPreferenceGroup(
                    title = stringResource(R.string.extension_install_behavior_per_source),
                ) {
                    behaviors.forEach { behavior ->
                        item {
                            SettingsChoicePreference(
                                title = behavior.title,
                                iconRes = behavior.iconRes,
                                value = behavior.policy,
                                options = policyOptions,
                                onValueChange = { policy ->
                                    onPolicyChange(behavior.type, policy)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
