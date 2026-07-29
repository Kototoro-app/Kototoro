package org.skepsun.kototoro.reader.ui.colorfilter

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.skepsun.kototoro.core.model.parcelable.ParcelableContent
import org.skepsun.kototoro.core.model.parcelable.ParcelableContentPage
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.require
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import javax.inject.Inject

@HiltViewModel
class ColorFilterConfigViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val settings: AppSettings,
	private val mangaDataRepository: ContentDataRepository,
) : BaseViewModel() {

	private val manga = savedStateHandle.require<ParcelableContent>(AppRouter.KEY_MANGA).manga

	private var initialColorFilter: ReaderColorFilter? = null
	val colorFilter = MutableStateFlow<ReaderColorFilter?>(null)
	val onDismiss = MutableEventFlow<Unit>()
	val preview = savedStateHandle.require<ParcelableContentPage>(AppRouter.KEY_PAGES).page

	val isChanged: Boolean
		get() = colorFilter.value != initialColorFilter

	init {
		launchLoadingJob {
			initialColorFilter = mangaDataRepository.getColorFilter(manga.id) ?: settings.readerColorFilter
			colorFilter.value = initialColorFilter
		}
	}

	fun setBrightness(brightness: Float) {
		updateColorFilter { it.copy(brightness = brightness) }
	}

	fun setContrast(contrast: Float) {
		updateColorFilter { it.copy(contrast = contrast) }
	}

	fun setInversion(invert: Boolean) {
		updateColorFilter { it.copy(isInverted = invert) }
	}

	fun setGrayscale(grayscale: Boolean) {
		updateColorFilter { it.copy(isGrayscale = grayscale) }
	}

	fun setBookEffect(book: Boolean) {
		updateColorFilter { it.copy(isBookBackground = book) }
	}

	fun reset() {
		colorFilter.value = null
	}

	fun setColorFilter(value: ReaderColorFilter?) {
		colorFilter.value = value
	}

	fun save() {
		launchLoadingJob(Dispatchers.Default) {
			mangaDataRepository.saveColorFilter(manga, colorFilter.value)
			onDismiss.call(Unit)
		}
	}

	fun saveGlobally() {
		launchLoadingJob(Dispatchers.Default) {
			settings.readerColorFilter = colorFilter.value
			mangaDataRepository.resetColorFilters()
			onDismiss.call(Unit)
		}
	}

	private inline fun updateColorFilter(block: (ReaderColorFilter) -> ReaderColorFilter) {
		colorFilter.value = block(
			colorFilter.value ?: ReaderColorFilter.EMPTY,
		).takeUnless { it.isEmpty }
	}
}
