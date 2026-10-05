package org.skepsun.kototoro.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Android's existing shape mapping; hosts select a style and optionally override its radius. */
fun kototoroShapes(tokens: InterfaceStyleTokens, radius: Dp = tokens.groupCornerRadius): Shapes = Shapes(
    extraSmall = RoundedCornerShape(tokens.controlCornerRadius.coerceAtMost(14.dp)),
    small = RoundedCornerShape(tokens.controlCornerRadius),
    medium = RoundedCornerShape(radius),
    large = RoundedCornerShape(tokens.groupCornerRadius),
    extraLarge = RoundedCornerShape(tokens.groupCornerRadius),
)
