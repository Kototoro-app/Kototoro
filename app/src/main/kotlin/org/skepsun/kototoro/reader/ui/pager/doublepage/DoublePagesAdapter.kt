package org.skepsun.kototoro.reader.ui.pager.doublepage

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.databinding.ItemPageBinding
import org.skepsun.kototoro.reader.domain.PageLoader
import org.skepsun.kototoro.reader.domain.ReaderPageEnhancementController
import org.skepsun.kototoro.reader.ui.config.ReaderSettings
import org.skepsun.kototoro.reader.ui.pager.BaseReaderAdapter

class DoublePagesAdapter(
	private val lifecycleOwner: LifecycleOwner,
	loader: PageLoader,
	enhancementController: ReaderPageEnhancementController,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
) : BaseReaderAdapter<DoublePageHolder>(
	loader,
	enhancementController,
	readerSettingsProducer,
	networkState,
	exceptionResolver,
) {
	private val backgroundCoordinator = DoublePageBackgroundCoordinator()

	override fun onBindViewHolder(holder: DoublePageHolder, position: Int) {
		val firstPosition = position and 1.inv()
		val firstPage = getItem(firstPosition)
		val secondPage = getItemOrNull(firstPosition + 1)
		holder.bind(
			data = getItem(position),
			backgroundKey = DoublePageBackgroundKey(firstPage.readerKey, secondPage?.readerKey),
		)
	}

	override fun onCreateViewHolder(
		parent: ViewGroup,
		loader: PageLoader,
		enhancementController: ReaderPageEnhancementController,
		readerSettingsProducer: ReaderSettings.Producer,
		networkState: NetworkState,
		exceptionResolver: ExceptionResolver,
	) = DoublePageHolder(
		owner = lifecycleOwner,
		binding = ItemPageBinding.inflate(LayoutInflater.from(parent.context), parent, false),
		loader = loader,
		enhancementController = enhancementController,
		readerSettingsProducer = readerSettingsProducer,
		networkState = networkState,
		exceptionResolver = exceptionResolver,
		backgroundCoordinator = backgroundCoordinator,
	)
}
