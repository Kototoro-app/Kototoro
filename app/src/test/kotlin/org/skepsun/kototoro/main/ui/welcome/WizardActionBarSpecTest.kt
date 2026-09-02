package org.skepsun.kototoro.main.ui.welcome

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WizardActionBarSpecTest {

    @Test
    fun `roomy presentation keeps the dot rail when everything fits`() {
        val spec = resolveWizardActionBar(
            availableWidth = 308.dp,
            backButtonWidth = 48.dp,
            dotsWidth = 104.dp,
            actionWidth = 100.dp,
        )
        spec.progress shouldBe WizardProgressPresentation.Roomy
        // back + gap + rail + gap + action
        spec.width shouldBe 284.dp
    }

    @Test
    fun `narrow bar drops the rail rather than squeeze the primary action`() {
        // 320dp phone: the rail no longer fits, and it — not the action — gives way.
        val spec = resolveWizardActionBar(
            availableWidth = 268.dp,
            backButtonWidth = 48.dp,
            dotsWidth = 104.dp,
            actionWidth = 100.dp,
        )
        spec.progress shouldBe WizardProgressPresentation.Compact
        // back + one gap + action: the action keeps all 100.dp of its measured width.
        spec.width shouldBe 164.dp
    }

    @Test
    fun `over-long localized label is clamped to the available width`() {
        val spec = resolveWizardActionBar(
            availableWidth = 308.dp,
            backButtonWidth = 48.dp,
            dotsWidth = 104.dp,
            actionWidth = 400.dp,
        )
        spec.progress shouldBe WizardProgressPresentation.Compact
        spec.width shouldBe 308.dp
    }

    @Test
    fun `wizard without a rail still hugs the two items`() {
        val spec = resolveWizardActionBar(
            availableWidth = 400.dp,
            backButtonWidth = 48.dp,
            dotsWidth = 0.dp,
            actionWidth = 100.dp,
        )
        spec.progress shouldBe WizardProgressPresentation.Compact
        spec.width shouldBe 164.dp
    }
}
