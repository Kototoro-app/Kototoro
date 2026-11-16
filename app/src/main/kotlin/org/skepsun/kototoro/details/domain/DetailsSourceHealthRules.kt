package org.skepsun.kototoro.details.domain

/**
 * Which warning, if any, the details page shows inline about the work's source.
 *
 * The page grew two competing explanations for the same cause: an inline "this source may be broken
 * (Not installed)" state and a floating "this source is not supported" snackbar raised by every
 * failing load. Only the first one is worth showing — it explains the cause and offers "Migrate".
 */
enum class DetailsSourceNoticeKind {
    NONE,
    UNINSTALLED,
    BROKEN,
    FAILING,
}

/**
 * The notice the details page should render.
 *
 * A recorded source-health verdict is authoritative when it exists. When it does not (health is only
 * queried for works in the library, and the query is asynchronous) a failed load still proves the
 * source cannot serve content right now, so the page falls back to "not installed" instead of staying
 * silent or pushing a snackbar over the primary action.
 */
fun detailsSourceNoticeKind(
    healthKind: DetailsSourceNoticeKind,
    sourceUnavailable: Boolean,
    healthWarningsEnabled: Boolean = true,
): DetailsSourceNoticeKind = when {
    healthKind != DetailsSourceNoticeKind.NONE &&
        (healthWarningsEnabled || !detailsSourceNoticeIsDismissible(healthKind)) -> healthKind
    sourceUnavailable -> DetailsSourceNoticeKind.UNINSTALLED
    else -> DetailsSourceNoticeKind.NONE
}

/**
 * Whether "don't show again" applies to this notice.
 *
 * "Broken" and "failing" are guesses from failed updates, and they often only mean no proxy was set,
 * so the user may silence them. "Not installed" is a fact, and the only explanation the page gives for
 * a work it cannot load, so it always stays.
 */
fun detailsSourceNoticeIsDismissible(kind: DetailsSourceNoticeKind): Boolean =
    kind == DetailsSourceNoticeKind.BROKEN || kind == DetailsSourceNoticeKind.FAILING

/**
 * Whether this failure is the page's own state rather than a reportable error.
 *
 * "This source cannot serve content" is exactly what the inline notice says, and the page guarantees
 * that notice exists for it (it falls back to [DetailsSourceNoticeKind.UNINSTALLED] when health was
 * never queried). Reporting it as a snackbar therefore adds a duplicate that covers the primary
 * action. Anything else (network, parse, auth) must still surface: nothing else tells the user.
 */
fun detailsOwnsSourceError(isUnsupportedSourceError: Boolean): Boolean = isUnsupportedSourceError
