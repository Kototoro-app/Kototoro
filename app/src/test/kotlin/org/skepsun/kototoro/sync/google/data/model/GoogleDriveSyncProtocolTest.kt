package org.skepsun.kototoro.sync.google.data.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GoogleDriveSyncProtocolTest {

	@Test
	fun `accepts current content v3 snapshots`() {
		assertTrue(
			GoogleDriveSyncSnapshot.isSupportedProtocol(
				schemaVersion = GoogleDriveSyncSnapshot.SCHEMA_VERSION,
				namespace = GoogleDriveSyncSnapshot.NAMESPACE_CONTENT_V3,
				semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
			),
		)
	}

	@Test
	fun `accepts work v2 snapshots written by versions before 2_2_0`() {
		assertTrue(
			GoogleDriveSyncSnapshot.isSupportedProtocol(
				schemaVersion = GoogleDriveSyncSnapshot.WORK_V2_SCHEMA_VERSION,
				namespace = GoogleDriveSyncSnapshot.NAMESPACE_WORK_V2,
				semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
			),
		)
	}

	@Test
	fun `rejects snapshots without protocol markers`() {
		assertFalse(GoogleDriveSyncSnapshot.isSupportedProtocol(null, null, null))
		assertFalse(
			GoogleDriveSyncSnapshot.isSupportedProtocol(
				schemaVersion = GoogleDriveSyncSnapshot.WORK_V2_SCHEMA_VERSION,
				namespace = null,
				semanticSchemaVersion = null,
			),
		)
	}

	@Test
	fun `rejects content v3 namespace with the old schema version`() {
		assertFalse(
			GoogleDriveSyncSnapshot.isSupportedProtocol(
				schemaVersion = GoogleDriveSyncSnapshot.WORK_V2_SCHEMA_VERSION,
				namespace = GoogleDriveSyncSnapshot.NAMESPACE_CONTENT_V3,
				semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
			),
		)
	}
}
