package org.skepsun.kototoro.core.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Android's `buildDeletePlan` keeps only what the page may actually remove; the folding itself lives here. */
class SourceDeletionRulesTest {

    private fun extension(name: String, packageId: String = "pkg.$name", packageName: String = "Ext $name") =
        SourceDeletionCandidate(name, packageId, packageName)

    private fun parserPlugin(name: String) =
        SourceDeletionCandidate(name, "plugin/$name.jar", "Plugin $name", uninstallable = false)

    private fun jsonSource(name: String) = SourceDeletionCandidate(name)

    private val candidates = listOf(
        extension("alpha", packageId = "pkg.a", packageName = "Alpha Extension"),
        extension("beta", packageId = "pkg.a", packageName = "Alpha Extension"),
        extension("gamma", packageId = "pkg.b", packageName = "Beta Extension"),
        parserPlugin("delta"),
        jsonSource("epsilon"),
    )

    @Test
    fun `one entry per package however many of its sources were picked`() {
        val plan = selectedSourcesDeletePlan(setOf("alpha", "beta"), candidates)
        assertEquals(listOf("pkg.a"), plan.packageIds)
        assertEquals(listOf("Alpha Extension"), plan.packageNames)
        assertTrue(plan.skippedNames.isEmpty())
        assertTrue(plan.hasPackages)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `several packages keep the candidate order`() {
        val plan = selectedSourcesDeletePlan(setOf("gamma", "beta", "alpha"), candidates)
        assertEquals(listOf("pkg.a", "pkg.b"), plan.packageIds)
        assertEquals(listOf("Alpha Extension", "Beta Extension"), plan.packageNames)
    }

    @Test
    fun `a parsers single-file package is skipped instead of deleted`() {
        val plan = selectedSourcesDeletePlan(setOf("delta", "alpha"), candidates)
        assertEquals(listOf("pkg.a"), plan.packageIds)
        assertEquals(listOf("Plugin delta"), plan.skippedNames)
        val onlySkipped = selectedSourcesDeletePlan(setOf("delta"), candidates)
        assertFalse(onlySkipped.hasPackages)
        assertFalse(onlySkipped.isEmpty)
        assertEquals(listOf("Plugin delta"), onlySkipped.skippedNames)
    }

    @Test
    fun `a source without a package contributes nothing`() {
        val plan = selectedSourcesDeletePlan(setOf("epsilon"), candidates)
        assertTrue(plan.isEmpty)
        assertFalse(plan.hasPackages)
        // Selection order never matters: the plan folds per package.
        assertEquals(selectedSourcesDeletePlan(setOf("alpha", "beta"), candidates),
            selectedSourcesDeletePlan(setOf("beta", "alpha"), candidates))
    }

    @Test
    fun `an empty or unknown selection plans nothing`() {
        assertTrue(selectedSourcesDeletePlan(emptySet(), candidates).isEmpty)
        assertTrue(selectedSourcesDeletePlan(setOf("zeta"), candidates).isEmpty)
        // A name the candidate list does not know is not silently reported as skipped either.
        assertTrue(selectedSourcesDeletePlan(setOf("zeta"), candidates).skippedNames.isEmpty())
    }

    @Test
    fun `a nameless package falls back to the source name`() {
        val plan = selectedSourcesDeletePlan(setOf("eta"),
            listOf(SourceDeletionCandidate("eta", "pkg.e", packageName = " "),
                SourceDeletionCandidate("theta", "plugin/t.jar", null, uninstallable = false)))
        assertEquals(listOf("eta"), plan.packageNames)
        assertEquals(listOf("theta"), selectedSourcesDeletePlan(setOf("theta"),
            listOf(SourceDeletionCandidate("theta", "plugin/t.jar", null, uninstallable = false))).skippedNames)
    }
}
