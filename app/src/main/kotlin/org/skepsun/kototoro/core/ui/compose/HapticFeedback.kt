package org.skepsun.kototoro.core.ui.compose

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

fun HapticFeedback.performSelectionHapticFeedback() {
    performHapticFeedback(HapticFeedbackType.TextHandleMove)
}
