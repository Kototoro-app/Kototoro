package org.skepsun.kototoro.mihon.util

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChildFirstClassLoaderPolicyTest {

	@Test
	fun `coroutines remain shared with the host`() {
		assertTrue(ChildFirstClassLoaderPolicy.shouldDelegateToParent("kotlinx.coroutines.BuildersKt"))
	}

	@Test
	fun `host api and platform classes remain shared`() {
		assertTrue(ChildFirstClassLoaderPolicy.shouldDelegateToParent("eu.kanade.tachiyomi.source.online.HttpSource"))
		assertTrue(ChildFirstClassLoaderPolicy.shouldDelegateToParent("android.content.Context"))
	}
}
