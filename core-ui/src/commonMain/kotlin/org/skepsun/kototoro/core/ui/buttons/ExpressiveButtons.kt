package org.skepsun.kototoro.core.ui.buttons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.FilledIconButton as M3FilledIconButton
import androidx.compose.material3.FilledTonalButton as M3FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton as M3FilledTonalIconButton
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.material3.TextButton as M3TextButton

/*
 * Drop-in replacements for the Material 3 buttons: same names and parameters, so a screen opts in by importing them
 * from this package instead of androidx.compose.material3. Under the Material 3 Expressive interface style they render
 * through the official `shapes` overloads, which morph the container towards a tighter corner while pressed; under any
 * other style, or when the caller passes its own [Shape], they are the plain Material buttons.
 */

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (shape == null && LocalMaterialExpressiveComponentsEnabled.current) {
        M3Button(onClick, ButtonDefaults.shapes(), modifier, enabled, colors, elevation, border, contentPadding,
            interactionSource, content)
    } else {
        M3Button(onClick, modifier, enabled, shape ?: ButtonDefaults.shape, colors, elevation, border, contentPadding,
            interactionSource, content)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (shape == null && LocalMaterialExpressiveComponentsEnabled.current) {
        M3FilledTonalButton(onClick, ButtonDefaults.shapes(), modifier, enabled, colors, elevation, border,
            contentPadding, interactionSource, content)
    } else {
        M3FilledTonalButton(onClick, modifier, enabled, shape ?: ButtonDefaults.filledTonalShape, colors, elevation,
            border, contentPadding, interactionSource, content)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (shape == null && LocalMaterialExpressiveComponentsEnabled.current) {
        M3OutlinedButton(onClick, ButtonDefaults.shapes(), modifier, enabled, colors, elevation, border,
            contentPadding, interactionSource, content)
    } else {
        M3OutlinedButton(onClick, modifier, enabled, shape ?: ButtonDefaults.outlinedShape, colors, elevation, border,
            contentPadding, interactionSource, content)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (shape == null && LocalMaterialExpressiveComponentsEnabled.current) {
        M3TextButton(onClick, ButtonDefaults.shapes(), modifier, enabled, colors, elevation, border, contentPadding,
            interactionSource, content)
    } else {
        M3TextButton(onClick, modifier, enabled, shape ?: ButtonDefaults.textShape, colors, elevation, border,
            contentPadding, interactionSource, content)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    shape: Shape? = null,
    content: @Composable () -> Unit,
) {
    if (shape == null && LocalMaterialExpressiveComponentsEnabled.current) {
        M3IconButton(onClick, IconButtonDefaults.shapes(), modifier, enabled, colors, interactionSource, content)
    } else {
        M3IconButton(onClick, modifier, enabled, colors, interactionSource, shape ?: IconButtonDefaults.standardShape,
            content)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    if (shape == null && LocalMaterialExpressiveComponentsEnabled.current) {
        M3FilledIconButton(onClick, IconButtonDefaults.shapes(), modifier, enabled, colors, interactionSource, content)
    } else {
        M3FilledIconButton(onClick, modifier, enabled, shape ?: IconButtonDefaults.filledShape, colors,
            interactionSource, content)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    if (shape == null && LocalMaterialExpressiveComponentsEnabled.current) {
        M3FilledTonalIconButton(onClick, IconButtonDefaults.shapes(), modifier, enabled, colors, interactionSource,
            content)
    } else {
        M3FilledTonalIconButton(onClick, modifier, enabled, shape ?: IconButtonDefaults.filledShape, colors,
            interactionSource, content)
    }
}
