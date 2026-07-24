package org.skepsun.kototoro.core.ui.dialog

import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.R as appcompatR
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.ShapeAppearanceModel
import org.skepsun.kototoro.core.util.ext.getThemeColor
import org.skepsun.kototoro.core.util.ext.resolveDp
import com.google.android.material.R as materialR

class BigButtonsAlertDialog private constructor(
	private val delegate: AlertDialog
) : DialogInterface by delegate {

	fun show() = delegate.show()

	class Builder(context: Context) {

		private val root = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			val padding = context.resolveThemeDimension(android.R.attr.dialogPreferredPadding)
			setPadding(padding, padding, padding, padding)
		}
		private val icon = AppCompatImageView(context).apply {
			layoutParams = LinearLayout.LayoutParams(context.resources.resolveDp(32), context.resources.resolveDp(32)).apply {
				gravity = Gravity.CENTER_HORIZONTAL
			}
			contentDescription = null
			imageTintList = android.content.res.ColorStateList.valueOf(
				context.getThemeColor(appcompatR.attr.colorPrimary, Color.TRANSPARENT),
			)
		}
		private val title = AppCompatTextView(context).apply {
			setTextAppearance(context, context.resolveThemeResource(materialR.attr.textAppearanceLabelLarge))
			textSize = 18f
			gravity = Gravity.CENTER
			textAlignment = View.TEXT_ALIGNMENT_CENTER
			layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
				setMargins(0, context.resources.resolveDp(18), 0, context.resources.resolveDp(18))
			}
		}
		private val button1 = createButton(context)
		private val button2 = createButton(context)
		private val button3 = createButton(context)

		init {
			root.addView(icon)
			root.addView(title)
			root.addView(button1)
			root.addView(button2)
			root.addView(button3)
		}

		private val delegate = MaterialAlertDialogBuilder(context)
			.setView(root)

		fun setTitle(@StringRes titleResId: Int): Builder {
			title.setText(titleResId)
			return this
		}

		fun setTitle(title: CharSequence): Builder {
			this.title.text = title
			return this
		}

		fun setIcon(@DrawableRes iconId: Int): Builder {
			icon.setImageResource(iconId)
			return this
		}

		fun setPositiveButton(
			@StringRes textId: Int,
			listener: DialogInterface.OnClickListener,
		): Builder {
			initButton(button1, DialogInterface.BUTTON_POSITIVE, textId, listener)
			return this
		}

		fun setNegativeButton(
			@StringRes textId: Int,
			listener: DialogInterface.OnClickListener? = null
		): Builder {
			initButton(button3, DialogInterface.BUTTON_NEGATIVE, textId, listener)
			return this
		}

		fun setNeutralButton(
			@StringRes textId: Int,
			listener: DialogInterface.OnClickListener? = null
		): Builder {
			initButton(button2, DialogInterface.BUTTON_NEUTRAL, textId, listener)
			return this
		}

		fun create(): BigButtonsAlertDialog {
			button1.adjustCorners(isFirst = true, isLast = button2.isGone() && button3.isGone())
			button2.adjustCorners(isFirst = button1.isGone(), isLast = button3.isGone())
			button3.adjustCorners(isFirst = button1.isGone() && button2.isGone(), isLast = true)

			val dialog = delegate.create()
			root.tag = dialog
			return BigButtonsAlertDialog(dialog)
		}

		private fun MaterialButton.adjustCorners(isFirst: Boolean, isLast: Boolean) {
			if (visibility != View.VISIBLE) {
				return
			}
			shapeAppearanceModel = shapeAppearanceModel.toBuilder().apply {
				if (!isFirst) {
					setTopLeftCornerSize(0f)
					setTopRightCornerSize(0f)
				}
				if (!isLast) {
					setBottomLeftCornerSize(0f)
					setBottomRightCornerSize(0f)
				}
			}.build()
		}

		private fun initButton(
			button: MaterialButton,
			which: Int,
			@StringRes textId: Int,
			listener: DialogInterface.OnClickListener?,
		) {
			button.setText(textId)
			button.visibility = View.VISIBLE
			button.setOnClickListener {
				val dialog = root.tag as DialogInterface
				listener?.onClick(dialog, which)
				dialog.dismiss()
			}
		}

		private fun MaterialButton.isGone() = visibility != View.VISIBLE

		private fun createButton(context: Context) = MaterialButton(
			context,
			null,
			materialR.attr.materialButtonTonalStyle,
		).apply {
			minimumHeight = context.resources.resolveDp(62)
			visibility = View.GONE
			layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
			shapeAppearanceModel = ShapeAppearanceModel.builder(
				context,
				0,
				materialR.attr.shapeAppearanceCornerMedium,
			).build()
		}
	}
}

private fun Context.resolveThemeDimension(@androidx.annotation.AttrRes attrResId: Int): Int =
	obtainStyledAttributes(intArrayOf(attrResId)).run {
		getDimensionPixelSize(0, 0).also { recycle() }
	}

private fun Context.resolveThemeResource(@androidx.annotation.AttrRes attrResId: Int): Int =
	obtainStyledAttributes(intArrayOf(attrResId)).run {
		getResourceId(0, 0).also { recycle() }
	}
