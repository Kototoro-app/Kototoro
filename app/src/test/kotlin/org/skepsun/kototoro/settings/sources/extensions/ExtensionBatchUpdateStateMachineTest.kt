package org.skepsun.kototoro.settings.sources.extensions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionBatchUpdateStateMachineTest {

	@Test
	fun `completed installs advance through queued packages then finish`() {
		val machine = ExtensionBatchUpdateStateMachine()

		assertTrue(machine.start(listOf("pkg.one", "pkg.two")))
		assertTrue(machine.inProgress.value)

		assertEquals(
			ExtensionBatchUpdateStateMachine.NextAction.InstallNext("pkg.one"),
			machine.nextAction(),
		)

		machine.beginInstall("pkg.one")
		machine.markInstallerIntentDispatched()

		assertEquals(
			ExtensionBatchUpdateStateMachine.NextAction.InstallNext("pkg.two"),
			machine.finishCurrentInstall(),
		)

		machine.beginInstall("pkg.two")
		machine.markInstallerIntentDispatched()

		assertEquals(
			ExtensionBatchUpdateStateMachine.NextAction.Completed,
			machine.finishCurrentInstall(),
		)
		assertFalse(machine.inProgress.value)
	}

	@Test
	fun `cancel stops in-progress batch and invokes cancel callback only before installer handoff`() {
		val cancelled = mutableListOf<String>()
		val machine = ExtensionBatchUpdateStateMachine()

		machine.start(listOf("pkg.one"))
		machine.beginInstall("pkg.one")

		machine.cancel(cancelled::add)

		assertEquals(listOf("pkg.one"), cancelled)
		assertFalse(machine.inProgress.value)
		assertEquals(ExtensionBatchUpdateStateMachine.NextAction.None, machine.nextAction())
	}

	@Test
	fun `cancel does not cancel package download after installer intent is dispatched`() {
		val cancelled = mutableListOf<String>()
		val machine = ExtensionBatchUpdateStateMachine()

		machine.start(listOf("pkg.one"))
		machine.beginInstall("pkg.one")
		machine.markInstallerIntentDispatched()

		machine.cancel(cancelled::add)

		assertTrue(cancelled.isEmpty())
		assertFalse(machine.inProgress.value)
	}
}
