package org.skepsun.kototoro.settings.sources.unified


import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.titleResId
import org.skepsun.kototoro.core.util.ext.getDisplayName
import org.skepsun.kototoro.core.util.ext.toLocaleOrNull
import org.skepsun.kototoro.extensions.recovery.SourceRecoveryStatus
import org.skepsun.kototoro.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.settings.sources.extensions.normalizeExtensionLanguageCode
import org.skepsun.kototoro.settings.sources.extensions.toInstalledIReaderPackageName
import java.util.Locale

@Composable
internal fun buildSourceSubtitle(item: UnifiedSourceItem): String {
    return listOfNotNull(
        item.kind.displayLabel(),
        stringResource(item.contentType.titleResId),
        item.language,
        item.repositoryName,
        item.packageName,
    ).joinToString(" · ")
}

@Composable
internal fun buildPackageSubtitle(item: UnifiedSourcePackageItem): String {
    return listOfNotNull(
        item.versionName?.let { "v$it" },
        item.repositoryName,
        stringResource(
            R.string.unified_sources_package_effective_count,
            item.activeSourceCount.coerceAtLeast(0),
            item.sourceCount,
        ),
    ).joinToString(" · ")
}

internal fun String?.normalizedLanguageTag(): String? {
    return this
        ?.normalizeExtensionLanguageCode()
        ?.takeIf { it.isNotBlank() }
        ?.uppercase(Locale.ROOT)
}

internal fun UnifiedSourcesUiState.Ready.buildDeletePlan(
    selectedSourceIds: Set<String>,
): UnifiedSelectedSourceDeletePlan {
    val packagesById = allPackages.associateBy { it.id }
    val deletablePackageIds = LinkedHashSet<String>()
    val deletablePackageNames = LinkedHashSet<String>()
    val skippedJarPackageNames = LinkedHashSet<String>()

    allSources
        .asSequence()
        .filter { it.id in selectedSourceIds }
        .forEach { item ->
            when {
                item.kind == UnifiedSourceKind.JAR -> {
                    skippedJarPackageNames += packagesById[item.packageId]?.name ?: item.packageName ?: item.title
                }
                item.packageId != null -> {
                    if (deletablePackageIds.add(item.packageId)) {
                        deletablePackageNames += packagesById[item.packageId]?.name ?: item.packageName ?: item.title
                    }
                }
            }
        }

    return UnifiedSelectedSourceDeletePlan(
        deletablePackageIds = deletablePackageIds.toList(),
        deletablePackageNames = deletablePackageNames.toList(),
        skippedJarPackageNames = skippedJarPackageNames.toList(),
    )
}

internal fun UnifiedSourcePackageItem.installedIconPackageName(): String? {
    if (!isInstalled) {
        return null
    }
        return when (kind) {
            UnifiedSourceKind.CLOUDSTREAM -> packageName
            UnifiedSourceKind.MIHON,
            UnifiedSourceKind.ANIYOMI,
            UnifiedSourceKind.TSUNDOKU -> packageName
            UnifiedSourceKind.IREADER -> packageName?.toInstalledIReaderPackageName()
            else -> null
    }
}

internal fun UnifiedSourceKind.packageIconRes(): Int {
        return when (this) {
            UnifiedSourceKind.JAR -> R.drawable.ic_file_zip
            UnifiedSourceKind.CLOUDSTREAM -> R.drawable.ic_source_cloudstream
            UnifiedSourceKind.MIHON -> R.drawable.ic_source_mihon
            UnifiedSourceKind.ANIYOMI -> R.drawable.ic_source_aniyomi
            UnifiedSourceKind.IREADER -> R.drawable.ic_source_ireader
            UnifiedSourceKind.TSUNDOKU -> R.drawable.ic_source_mihon
        UnifiedSourceKind.LEGADO -> R.drawable.ic_source_legado
        UnifiedSourceKind.TVBOX -> R.drawable.ic_source_tvbox
        UnifiedSourceKind.JS -> R.drawable.ic_source_js
        UnifiedSourceKind.LNREADER -> R.drawable.ic_source_lnreader
        UnifiedSourceKind.NATIVE -> R.drawable.ic_source_builtin
    }
}

@Composable
internal fun UnifiedSourcePackageState.displayLabel(): String {
    return when (this) {
        UnifiedSourcePackageState.AVAILABLE -> stringResource(R.string.available)
        UnifiedSourcePackageState.UPDATE_AVAILABLE -> stringResource(R.string.update)
        UnifiedSourcePackageState.INSTALLED -> stringResource(R.string.installed)
        UnifiedSourcePackageState.INSTALLING -> stringResource(R.string.installing_extension)
        UnifiedSourcePackageState.UNTRUSTED -> stringResource(R.string.untrusted_extension)
        UnifiedSourcePackageState.INCOMPATIBLE -> stringResource(R.string.incompatible_extension)
    }
}

internal val UnifiedSourcePackageState.isWarning: Boolean
    get() = this == UnifiedSourcePackageState.UNTRUSTED || this == UnifiedSourcePackageState.INCOMPATIBLE

@Composable
internal fun UnifiedSourcePackageItem.primaryActionLabel(): String {
    val isLocalApkAction = kind.isSideloadKind()
    return when (state) {
        UnifiedSourcePackageState.AVAILABLE -> stringResource(
            if (isLocalApkAction) R.string.sideload_extension else R.string.install_extension,
        )
        UnifiedSourcePackageState.UPDATE_AVAILABLE -> stringResource(
            if (isLocalApkAction) R.string.update_sideload_extension else R.string.update_extension,
        )
        UnifiedSourcePackageState.UNTRUSTED,
        UnifiedSourcePackageState.INCOMPATIBLE -> stringResource(R.string.details)
        UnifiedSourcePackageState.INSTALLING,
        UnifiedSourcePackageState.INSTALLED -> ""
    }
}

@Composable
internal fun CompactActionChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 30.dp),
        label = {
            Box(modifier = Modifier.padding(horizontal = 2.dp)) {
                label()
            }
        },
    )
}

internal enum class CompactTagTone {
    Neutral,
    Warning,
    TestedAvailable,
    TestedUnavailable,
}

@Composable
internal fun CompactTag(
    text: String,
    modifier: Modifier = Modifier,
    isWarning: Boolean = false,
    tone: CompactTagTone? = null,
) {
    val resolvedTone = tone ?: if (isWarning) CompactTagTone.Warning else CompactTagTone.Neutral
    val (containerColor, contentColor) = when (resolvedTone) {
        CompactTagTone.Neutral ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        CompactTagTone.Warning ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        CompactTagTone.TestedAvailable ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        CompactTagTone.TestedUnavailable ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun UnifiedSourceKind.displayLabel(): String {
    return stringResource(labelResId())
}

internal fun UnifiedSourceKind.labelResId(): Int {
        return when (this) {
            UnifiedSourceKind.NATIVE -> R.string.source_type_native
            UnifiedSourceKind.JAR -> R.string.source_type_jar
            UnifiedSourceKind.CLOUDSTREAM -> R.string.source_type_cloudstream
            UnifiedSourceKind.MIHON -> R.string.source_type_mihon
            UnifiedSourceKind.ANIYOMI -> R.string.source_type_aniyomi
            UnifiedSourceKind.IREADER -> R.string.source_type_ireader
            UnifiedSourceKind.TSUNDOKU -> R.string.source_type_tsundoku
        UnifiedSourceKind.LEGADO -> R.string.source_type_legado
        UnifiedSourceKind.TVBOX -> R.string.source_type_tvbox
        UnifiedSourceKind.JS -> R.string.source_type_js
        UnifiedSourceKind.LNREADER -> R.string.source_type_lnreader
    }
}

internal fun UnifiedSourceKind.dialogLabelResId(): Int {
        return when (this) {
            UnifiedSourceKind.NATIVE -> R.string.source_type_builtin_short
            UnifiedSourceKind.JAR -> R.string.source_type_jar
            UnifiedSourceKind.CLOUDSTREAM -> R.string.source_type_cloudstream
            UnifiedSourceKind.MIHON -> R.string.source_type_mihon_apk
            UnifiedSourceKind.ANIYOMI -> R.string.source_type_aniyomi_apk
            UnifiedSourceKind.IREADER -> R.string.source_type_ireader_apk
            UnifiedSourceKind.TSUNDOKU -> R.string.source_type_tsundoku_apk
        UnifiedSourceKind.LEGADO -> R.string.source_type_legado_json
        UnifiedSourceKind.TVBOX -> R.string.source_type_tvbox_json
        UnifiedSourceKind.JS -> R.string.source_type_js_source
        UnifiedSourceKind.LNREADER -> R.string.source_type_lnreader
    }
}

internal fun UnifiedSourceKind.supportsJsonImport(): Boolean {
    return when (this) {
        UnifiedSourceKind.LEGADO,
        UnifiedSourceKind.TVBOX,
        UnifiedSourceKind.JS,
        UnifiedSourceKind.LNREADER -> true
        else -> false
    }
}

internal fun UnifiedThirdPartyAction.installKindOrNull(): UnifiedSourceKind? {
    return when (this) {
        is UnifiedThirdPartyAction.AddRepositoryUrl -> kind.takeIf { it == UnifiedSourceKind.JS }
        is UnifiedThirdPartyAction.AddInlineRepository -> kind
        is UnifiedThirdPartyAction.OpenRepositoryFile -> kind
        UnifiedThirdPartyAction.OpenLocalJar -> null
    }
}

internal fun UnifiedSourceKind.isSideloadKind(): Boolean {
        return when (this) {
            UnifiedSourceKind.MIHON,
            UnifiedSourceKind.ANIYOMI,
            UnifiedSourceKind.IREADER -> true
            else -> false
    }
}

@Composable
internal fun UnifiedRepositoryLocationType.displayLabel(): String {
    return when (this) {
        UnifiedRepositoryLocationType.REMOTE_URL -> stringResource(R.string.remote_url)
        UnifiedRepositoryLocationType.LOCAL_FILE -> stringResource(R.string.local_file)
        UnifiedRepositoryLocationType.INLINE_IMPORT -> stringResource(R.string.repository_location_inline)
        UnifiedRepositoryLocationType.PRESET_ONLY -> stringResource(R.string.repository_location_preset)
    }
}

@Composable
internal fun String.displayLanguageLabel(): String {
    if (isBlank()) return stringResource(R.string.multi_language_short)
    val extensionLabel = getExternalExtensionLanguageDisplayName(this)
    if (extensionLabel != uppercase(Locale.ROOT)) {
        return extensionLabel
    }
    val locale = toLocaleOrNull() ?: Locale.forLanguageTag(this)
    return locale.getDisplayName(LocalContext.current).ifBlank { uppercase() }
}

@Composable
internal fun UnifiedEnabledFilter.displayLabel(): String {
    return when (this) {
        UnifiedEnabledFilter.ALL -> stringResource(R.string.all)
        UnifiedEnabledFilter.ENABLED -> stringResource(R.string.enabled)
        UnifiedEnabledFilter.DISABLED -> stringResource(R.string.disabled)
    }
}

@Composable
internal fun UnifiedAvailabilityFilter.displayLabel(): String {
    return when (this) {
        UnifiedAvailabilityFilter.ALL -> stringResource(R.string.all)
        UnifiedAvailabilityFilter.AVAILABLE -> stringResource(R.string.available)
        UnifiedAvailabilityFilter.UNAVAILABLE -> stringResource(R.string.unavailable)
    }
}

@Composable
internal fun UnifiedTestAvailabilityFilter.displayLabel(): String {
    return when (this) {
        UnifiedTestAvailabilityFilter.ALL -> stringResource(R.string.all)
        UnifiedTestAvailabilityFilter.UNTESTED -> stringResource(R.string.source_untested)
        UnifiedTestAvailabilityFilter.AVAILABLE -> stringResource(R.string.available)
        UnifiedTestAvailabilityFilter.UNAVAILABLE -> stringResource(R.string.unavailable)
    }
}

@Composable
internal fun UnifiedNsfwFilter.displayLabel(): String {
    return when (this) {
        UnifiedNsfwFilter.ALL -> stringResource(R.string.all)
        UnifiedNsfwFilter.SFW -> stringResource(R.string.sfw)
        UnifiedNsfwFilter.NSFW -> stringResource(R.string.nsfw)
    }
}

internal fun UnifiedSourcesFilterState.otherFilterCount(): Int {
    return locationTypes.size +
        (if (enabledFilter == UnifiedEnabledFilter.ALL) 0 else 1) +
        (if (availabilityFilter == UnifiedAvailabilityFilter.ALL) 0 else 1) +
        (if (testAvailabilityFilter == UnifiedTestAvailabilityFilter.ALL) 0 else 1) +
        (if (nsfwFilter == UnifiedNsfwFilter.ALL) 0 else 1)
}

private val topBarContentTypes = linkedSetOf(
    ContentType.MANGA,
    ContentType.NOVEL,
    ContentType.VIDEO,
)

private fun Set<ContentType>.primaryContentType(): ContentType? {
    return singleOrNull { it in topBarContentTypes }
}

// --- Source recovery (T5.1 / T5.4 / T5.6) --------------------------------------------------
//
// Colors follow the package-group palette approach: fixed light/dark pairs so the badge
// stays readable in both themes without fighting the Material role system.

/** Per-status badge label. */
internal fun SourceRecoveryStatus.recoveryLabelRes(): Int {
    return when (this) {
        SourceRecoveryStatus.RESOLVED -> R.string.recovery_status_resolved
        SourceRecoveryStatus.MISSING -> R.string.recovery_status_missing
        SourceRecoveryStatus.REPOSITORY_REQUIRED -> R.string.recovery_status_repository
        SourceRecoveryStatus.SIDELOAD_REQUIRED -> R.string.recovery_status_sideload
        SourceRecoveryStatus.REIMPORT_REQUIRED -> R.string.recovery_status_reimport
        SourceRecoveryStatus.SIGNATURE_CONFIRMATION_REQUIRED -> R.string.recovery_status_signature
    }
}

/** Label for the per-source recovery action button. */
internal fun SourceRecoveryStatus.recoveryActionLabelRes(): Int {
    return when (this) {
        SourceRecoveryStatus.REPOSITORY_REQUIRED,
        SourceRecoveryStatus.MISSING -> R.string.recovery_action
        SourceRecoveryStatus.SIDELOAD_REQUIRED -> R.string.recovery_action_sideload
        SourceRecoveryStatus.REIMPORT_REQUIRED -> R.string.recovery_action_reimport
        SourceRecoveryStatus.SIGNATURE_CONFIRMATION_REQUIRED -> R.string.recovery_action_confirm
        SourceRecoveryStatus.RESOLVED -> R.string.recovery_action
    }
}

/** Container/content color pair for the recovery badge, adapted to the current theme. */
@Composable
internal fun SourceRecoveryStatus.recoveryTagColors(): Pair<Color, Color> {
    val dark = isSystemInDarkTheme()
    return when (this) {
        SourceRecoveryStatus.MISSING -> if (dark) {
            Color(0xFF4A2422) to Color(0xFFF3C6C4)
        } else {
            Color(0xFFF9DEDC) to Color(0xFF7D1F1C)
        }
        SourceRecoveryStatus.REPOSITORY_REQUIRED -> if (dark) {
            Color(0xFF4A3A24) to Color(0xFFF2D8B6)
        } else {
            Color(0xFFFAE7C9) to Color(0xFF7D5216)
        }
        SourceRecoveryStatus.SIDELOAD_REQUIRED -> if (dark) {
            Color(0xFF23384E) to Color(0xFFBBD7F5)
        } else {
            Color(0xFFD9EBFA) to Color(0xFF1E5090)
        }
        SourceRecoveryStatus.REIMPORT_REQUIRED -> if (dark) {
            Color(0xFF213A36) to Color(0xFFB3DDD3)
        } else {
            Color(0xFFD7EFE9) to Color(0xFF1F6B5C)
        }
        SourceRecoveryStatus.SIGNATURE_CONFIRMATION_REQUIRED -> if (dark) {
            Color(0xFF382A4D) to Color(0xFFD7C2F0)
        } else {
            Color(0xFFEBDEF9) to Color(0xFF5A3A8C)
        }
        SourceRecoveryStatus.RESOLVED ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
}

/** Small colored badge shown on a source row when its origin is not fully resolved. */
@Composable
internal fun RecoveryStatusTag(
    status: SourceRecoveryStatus,
    modifier: Modifier = Modifier,
) {
    val (containerColor, contentColor) = status.recoveryTagColors()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = stringResource(status.recoveryLabelRes()),
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

