package org.skepsun.kototoro.details.ui.model

data class DetailsSourceDisplayStrings(
    val unavailableText: String,
    val metadataBindingLabel: String,
    val readingSourceLabel: String,
)

enum class DetailsSourceRole {
    METADATA,
    READING_SOURCE,
}

data class DetailsSourceDisplayContext(
    val role: DetailsSourceRole,
    val currentContentTitle: String? = null,
    val currentContentSourceName: String? = null,
    val linkedTrackingTitle: String? = null,
    val resolvedSourceTitle: String = "",
    val resolvedTrackingTitle: String = "",
    val strings: DetailsSourceDisplayStrings,
)

data class DetailsSourcePresentationModel(
    val title: String,
    val subtitle: String,
)

fun DetailsSourceOption.toPresentationModel(
    context: DetailsSourceDisplayContext,
): DetailsSourcePresentationModel {
    val title = when {
        !title.isNullOrBlank() -> title.orEmpty()
        source != null &&
            context.currentContentSourceName == source.name &&
            !context.currentContentTitle.isNullOrBlank() -> context.currentContentTitle.orEmpty()
        !context.linkedTrackingTitle.isNullOrBlank() -> context.linkedTrackingTitle.orEmpty()
        context.resolvedSourceTitle.isNotBlank() -> context.resolvedSourceTitle
        context.resolvedTrackingTitle.isNotBlank() -> context.resolvedTrackingTitle
        remoteId != null -> "#$remoteId"
        else -> context.strings.unavailableText
    }
    val channelLabel = when {
        context.resolvedTrackingTitle.isNotBlank() -> context.resolvedTrackingTitle
        context.resolvedSourceTitle.isNotBlank() -> context.resolvedSourceTitle
        !subtitle.isNullOrBlank() -> subtitle.orEmpty()
        remoteId != null -> "#$remoteId"
        else -> ""
    }
    val roleLabel = when (context.role) {
        DetailsSourceRole.METADATA -> context.strings.metadataBindingLabel
        DetailsSourceRole.READING_SOURCE -> context.strings.readingSourceLabel
    }
    val subtitle = buildString {
        append(roleLabel)
        if (channelLabel.isNotBlank()) {
            append(" · ")
            append(channelLabel)
        }
    }
    return DetailsSourcePresentationModel(
        title = title,
        subtitle = subtitle,
    )
}
