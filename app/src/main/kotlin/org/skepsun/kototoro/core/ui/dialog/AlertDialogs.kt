package org.skepsun.kototoro.core.ui.dialog

import android.content.Context
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CompoundButton.OnCheckedChangeListener
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.annotation.UiContext
import androidx.appcompat.app.AlertDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.R as materialR

inline fun buildAlertDialog(
    @UiContext context: Context,
    isCentered: Boolean = false,
    block: MaterialAlertDialogBuilder.() -> Unit,
): AlertDialog = MaterialAlertDialogBuilder(
    context,
    if (isCentered) materialR.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered else 0,
).apply(block).create()

fun <B : AlertDialog.Builder> B.setCheckbox(
    @StringRes textResId: Int,
    isChecked: Boolean,
    onCheckedChangeListener: OnCheckedChangeListener
) = apply {
    val checkbox = MaterialCheckBox(context).apply {
        setText(textResId)
        this.isChecked = isChecked
        setOnCheckedChangeListener(onCheckedChangeListener)
    }
    val container = FrameLayout(context).apply {
        setPaddingRelative(
            context.resolveThemeDimension(android.R.attr.listPreferredItemPaddingStart),
            0,
            context.resolveThemeDimension(android.R.attr.listPreferredItemPaddingEnd),
            0,
        )
        addView(checkbox, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    setView(container)
}

private fun Context.resolveThemeDimension(@androidx.annotation.AttrRes attrResId: Int): Int =
    obtainStyledAttributes(intArrayOf(attrResId)).run {
        getDimensionPixelSize(0, 0).also { recycle() }
    }
