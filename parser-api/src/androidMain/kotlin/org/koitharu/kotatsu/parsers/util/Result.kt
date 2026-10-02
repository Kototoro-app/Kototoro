package org.koitharu.kotatsu.parsers.util

import kotlinx.coroutines.CancellationException
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@Suppress("WRONG_INVOCATION_KIND") // https://youtrack.jetbrains.com/issue/KT-70714
public inline fun <T, R> T.runCatchingCancellable(block: T.() -> R): Result<R> {
	contract {
		callsInPlace(block, InvocationKind.EXACTLY_ONCE)
	}
	return try {
		Result.success(block())
	} catch (e: InterruptedException) {
		throw e
	} catch (e: CancellationException) {
		throw e
	} catch (e: Throwable) {
		Result.failure(e)
	}
}

public inline fun <R, T : R> Result<T>.recoverCatchingCancellable(transform: (exception: Throwable) -> R): Result<R> {
	contract {
		callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
	}
	return when (val exception = exceptionOrNull()) {
		null -> this
		else -> runCatchingCancellable { transform(exception) }
	}
}

public inline fun <R : Any, T : R> Result<T>.recoverNotNull(transform: (exception: Throwable) -> R?): Result<R> {
	contract {
		callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
	}
	return when (val exception = exceptionOrNull()) {
		null -> this
		else -> transform(exception)?.let(Result.Companion::success) ?: this
	}
}
