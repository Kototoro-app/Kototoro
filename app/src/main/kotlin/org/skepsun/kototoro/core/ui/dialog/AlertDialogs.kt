package org.skepsun.kototoro.core.ui.dialog

import android.content.Context
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.CompoundButton.OnCheckedChangeListener
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.annotation.StringRes
import androidx.annotation.UiContext
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatAutoCompleteTextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hannesdorfmann.adapterdelegates4.AdapterDelegate
import com.hannesdorfmann.adapterdelegates4.AdapterDelegatesManager
import com.hannesdorfmann.adapterdelegates4.ListDelegationAdapter
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.resolveDp
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

fun <B : AlertDialog.Builder, T> B.setRecyclerViewList(
    list: List<T>,
    delegate: AdapterDelegate<List<T>>,
) = apply {
    val delegatesManager = AdapterDelegatesManager<List<T>>()
    delegatesManager.addDelegate(delegate)
    setRecyclerViewList(ListDelegationAdapter(delegatesManager).also { it.items = list })
}

fun <B : AlertDialog.Builder, T> B.setRecyclerViewList(
    list: List<T>,
    vararg delegates: AdapterDelegate<List<T>>,
) = apply {
    val delegatesManager = AdapterDelegatesManager<List<T>>()
    delegates.forEach { delegatesManager.addDelegate(it) }
    setRecyclerViewList(ListDelegationAdapter(delegatesManager).also { it.items = list })
}

fun <B : AlertDialog.Builder> B.setRecyclerViewList(adapter: RecyclerView.Adapter<*>) = apply {
    val recyclerView = RecyclerView(context)
    recyclerView.layoutManager = LinearLayoutManager(context)
    recyclerView.updatePadding(
        top = context.resources.getDimensionPixelOffset(R.dimen.list_spacing),
    )
    recyclerView.clipToPadding = false
    recyclerView.adapter = adapter
    setView(recyclerView)
}

fun <B : AlertDialog.Builder> B.setEditText(
    inputType: Int,
    singleLine: Boolean,
): EditText {
    val editText = AppCompatEditText(context)
    editText.inputType = inputType
    if (singleLine) {
        editText.setSingleLine()
        editText.imeOptions = EditorInfo.IME_ACTION_DONE
    }
    val layout = FrameLayout(context)
    val lp = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    val horizontalMargin = context.resources.getDimensionPixelOffset(R.dimen.screen_padding)
    lp.setMargins(
        horizontalMargin,
        context.resources.getDimensionPixelOffset(R.dimen.margin_small),
        horizontalMargin,
        0,
    )
    layout.addView(editText, lp)
    setView(layout)
    return editText
}

fun <B : AlertDialog.Builder> B.setEditText(
    entries: List<CharSequence>,
    inputType: Int,
    singleLine: Boolean,
): EditText {
    if (entries.isEmpty()) {
        return setEditText(inputType, singleLine)
    }
    val autoCompleteTextView = AppCompatAutoCompleteTextView(context)
    val dropdown = AppCompatImageButton(context).apply {
        id = android.view.View.generateViewId()
        setImageResource(R.drawable.ic_expand_more)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = null
        setPadding(0, 0, 0, context.resources.resolveDp(2))
        background = context.obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackgroundBorderless),
        ).run {
            getDrawable(0).also { recycle() }
        }
    }
    autoCompleteTextView.setAdapter(
        ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, entries),
    )
    dropdown.setOnClickListener {
        autoCompleteTextView.showDropDown()
    }
    autoCompleteTextView.inputType = inputType
    if (singleLine) {
        autoCompleteTextView.setSingleLine()
        autoCompleteTextView.imeOptions = EditorInfo.IME_ACTION_DONE
    }
    val container = RelativeLayout(context).apply {
        setPaddingRelative(
            context.resources.getDimensionPixelOffset(R.dimen.screen_padding),
            context.resources.getDimensionPixelOffset(R.dimen.margin_small),
            context.resources.getDimensionPixelOffset(R.dimen.screen_padding),
            0,
        )
        addView(
            autoCompleteTextView,
            RelativeLayout.LayoutParams(0, WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
                addRule(RelativeLayout.START_OF, dropdown.id)
            },
        )
        addView(
            dropdown,
            RelativeLayout.LayoutParams(context.resources.resolveDp(48), context.resources.resolveDp(48)).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            },
        )
    }
    setView(container)
    return autoCompleteTextView
}

private fun Context.resolveThemeDimension(@androidx.annotation.AttrRes attrResId: Int): Int =
    obtainStyledAttributes(intArrayOf(attrResId)).run {
        getDimensionPixelSize(0, 0).also { recycle() }
    }
