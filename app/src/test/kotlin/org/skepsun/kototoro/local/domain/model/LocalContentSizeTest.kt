package org.skepsun.kototoro.local.domain.model

import com.hippo.unifile.UniFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LocalContentSizeTest {

	@TempDir
	lateinit var root: File

	@Test
	fun `computes nested UniFile content size`() {
		File(root, "page-1.jpg").writeBytes(ByteArray(7))
		File(root, "chapter").mkdirs()
		File(root, "chapter/page-2.png").writeBytes(ByteArray(11))

		val size = checkNotNull(UniFile.fromFile(root)).computeTreeSize()

		assertEquals(18L, size)
	}
}
