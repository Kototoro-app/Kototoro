package org.skepsun.kototoro.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.FragmentContainerView
import androidx.viewbinding.ViewBinding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import org.skepsun.kototoro.R

class SettingsActivityLayoutBinding private constructor(
	private val rootView: View,
	val legacyTopBarHost: View,
	val appbar: AppBarLayout,
	val toolbar: MaterialToolbar,
	val containerCompose: ComposeView,
) : ViewBinding {

	override fun getRoot(): View = rootView

	companion object {

		fun inflate(layoutInflater: LayoutInflater): SettingsActivityLayoutBinding {
			val context = layoutInflater.context
			val root = CoordinatorLayout(context).apply {
				layoutParams = ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT,
				)
			}
			val appbar = AppBarLayout(context).apply {
				id = R.id.appbar
				visibility = View.GONE
				addView(
					MaterialToolbar(context).apply {
						id = R.id.toolbar
					},
					AppBarLayout.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT,
						ViewGroup.LayoutParams.WRAP_CONTENT,
					),
				)
			}
			val fragmentContainer = FragmentContainerView(context).apply {
				id = R.id.container
				visibility = View.GONE
				layoutParams = CoordinatorLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT,
				).apply {
					behavior = AppBarLayout.ScrollingViewBehavior()
				}
			}
			val composeContainer = ComposeView(context).apply {
				id = R.id.container_compose
				layoutParams = CoordinatorLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT,
				)
			}
			val searchContainer = FragmentContainerView(context).apply {
				id = R.id.container_search
				visibility = View.GONE
				layoutParams = CoordinatorLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT,
				).apply {
					behavior = AppBarLayout.ScrollingViewBehavior()
				}
			}
			root.addView(appbar)
			root.addView(fragmentContainer)
			root.addView(composeContainer)
			root.addView(searchContainer)
			return SettingsActivityLayoutBinding(
				rootView = root,
				legacyTopBarHost = appbar,
				appbar = appbar,
				toolbar = appbar.requireViewByIdCompat(R.id.toolbar),
				containerCompose = composeContainer,
			)
		}

		private fun <T : View> View.requireViewByIdCompat(id: Int): T {
			return requireNotNull(findViewById(id)) {
				"Missing required view with id=${context.resources.getResourceName(id)}"
			}
		}
	}
}
