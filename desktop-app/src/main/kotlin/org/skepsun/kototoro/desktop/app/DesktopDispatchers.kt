package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The threads the Windows app runs its work on, named by what the work does rather than by who started it.
 *
 * Android separates the same three kinds with its own executors (IO, default, WorkManager). One shared `IO`
 * dispatcher for everything was the wrong split on the desktop:
 *
 * * Image decoding is CPU-bound (Skia/ImageIO), so running it on [io] parked up to 64 blocking-IO threads in
 *   native codecs while network calls queued behind them.
 * * The periodic jobs (tracker, suggestions, app update) each hold a lease for as long as the network takes;
 *   sharing one dispatcher let a slow tracker run push an update check arbitrarily far back.
 *
 * [decode] and [background] are `limitedParallelism` views over [Dispatchers.Default], so they never create extra
 * threads: they only bound how much of the shared CPU pool each kind of work may hold at once. Pixel budgets stay
 * where they were — [DesktopImageDecoder.permits] still caps concurrent decodes at two.
 */
internal object DesktopDispatchers {
    /** Blocking file and network work that suspends on a handle (OkHttp/HttpClient, NIO reads and writes). */
    val io: CoroutineDispatcher = Dispatchers.IO

    /** CPU-bound image decode; two workers, matching the decoder's own permit count. */
    val decode: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)

    /** The periodic jobs, serialised so two of them never run beside each other by accident. */
    val background: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
}
