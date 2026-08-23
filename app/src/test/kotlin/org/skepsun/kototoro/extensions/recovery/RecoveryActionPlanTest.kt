package org.skepsun.kototoro.extensions.recovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.entity.SourceOriginEntity

class RecoveryActionPlanTest {

    @Test
    fun `repository required maps to install from repository with url passthrough`() {
        val origin = origin(repositoryUrl = "https://repo.example.com/index.min.json")

        val plan = planRecoveryAction(SourceRecoveryStatus.REPOSITORY_REQUIRED, origin)

        val install = assertInstanceOf(RecoveryActionPlan.InstallFromRepository::class.java, plan)
        assertEquals("https://repo.example.com/index.min.json", install.repositoryUrl)
    }

    @Test
    fun `sideload required maps to install sideload with package and kind`() {
        val origin = origin(kind = "MIHON", packageName = "eu.kanade.tachiyomi.extension.en.example")

        val plan = planRecoveryAction(SourceRecoveryStatus.SIDELOAD_REQUIRED, origin)

        val sideload = assertInstanceOf(RecoveryActionPlan.InstallSideload::class.java, plan)
        assertEquals("eu.kanade.tachiyomi.extension.en.example", sideload.packageName)
        assertEquals("MIHON", sideload.kind)
    }

    @Test
    fun `sideload required keeps null package name`() {
        val origin = origin(kind = "TSUNDOKU")

        val plan = planRecoveryAction(SourceRecoveryStatus.SIDELOAD_REQUIRED, origin)

        val sideload = assertInstanceOf(RecoveryActionPlan.InstallSideload::class.java, plan)
        assertEquals(null, sideload.packageName)
        assertEquals("TSUNDOKU", sideload.kind)
    }

    @Test
    fun `reimport required maps to reimport with locator passthrough`() {
        val origin = origin(kind = "LEGADO", locator = "https://books.example.com/api/source/42")

        val plan = planRecoveryAction(SourceRecoveryStatus.REIMPORT_REQUIRED, origin)

        val reimport = assertInstanceOf(RecoveryActionPlan.Reimport::class.java, plan)
        assertEquals("https://books.example.com/api/source/42", reimport.locator)
    }

    @Test
    fun `signature confirmation required maps to confirm signature with recorded digest`() {
        val origin = origin(packageName = "com.example.src", signingDigest = "sha256:abc123")

        val plan = planRecoveryAction(SourceRecoveryStatus.SIGNATURE_CONFIRMATION_REQUIRED, origin)

        val confirm = assertInstanceOf(RecoveryActionPlan.ConfirmSignature::class.java, plan)
        assertEquals("sha256:abc123", confirm.expectedDigest)
    }

    @Test
    fun `signature confirmation required keeps null digest`() {
        val origin = origin(packageName = "com.example.src")

        val plan = planRecoveryAction(SourceRecoveryStatus.SIGNATURE_CONFIRMATION_REQUIRED, origin)

        val confirm = assertInstanceOf(RecoveryActionPlan.ConfirmSignature::class.java, plan)
        assertEquals(null, confirm.expectedDigest)
    }

    @Test
    fun `missing maps to no action`() {
        val plan = planRecoveryAction(SourceRecoveryStatus.MISSING, origin())

        assertEquals(RecoveryActionPlan.NoActionMissing, plan)
    }

    @Test
    fun `resolved maps to no action even with recovery hints present`() {
        // The derivation never yields REPOSITORY_REQUIRED together with installed=true, but a
        // defensive caller could hand this function any status; RESOLVED must be a no-op.
        val origin = origin(repositoryUrl = "https://repo.example.com")

        val plan = planRecoveryAction(SourceRecoveryStatus.RESOLVED, origin)

        assertEquals(RecoveryActionPlan.NoActionMissing, plan)
    }

    @Test
    fun `repository required with null url degrades to empty-string plan instead of crashing`() {
        val origin = origin(repositoryUrl = null)

        val plan = planRecoveryAction(SourceRecoveryStatus.REPOSITORY_REQUIRED, origin)

        val install = assertInstanceOf(RecoveryActionPlan.InstallFromRepository::class.java, plan)
        assertEquals("", install.repositoryUrl)
    }

    private fun origin(
        sourceKey: String = "MIHON_kototoro.test",
        kind: String = "MIHON",
        packageName: String? = null,
        repositoryUrl: String? = null,
        locator: String? = null,
        signingDigest: String? = null,
    ): SourceOriginEntity = SourceOriginEntity(
        sourceKey = sourceKey,
        kind = kind,
        displayName = "Example",
        contentType = "manga",
        packageName = packageName,
        sourceId = null,
        repositoryUrl = repositoryUrl,
        repositoryName = null,
        locator = locator,
        versionName = null,
        versionCode = null,
        signingDigest = signingDigest,
        lastSeenAt = null,
        updatedAt = 0L,
    )
}
