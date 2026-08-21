package org.skepsun.kototoro.settings.sources.unified

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeTypeOf
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentType

class UnifiedSourcesGroupingTest : FunSpec({

	test("single-source packages and package-less sources stay flat") {
		val a = sourceItem(id = "src-a")
		val b = sourceItem(id = "src-b", packageId = "pkg:B", packageName = "B")

		val rows = buildGroupedUnifiedSourceRows(listOf(a, b), emptySet())

		rows shouldHaveSize 2
		rows[0] shouldBe UnifiedSourceDisplayRow.SourceItem(a)
		rows[1] shouldBe UnifiedSourceDisplayRow.SourceItem(b)
	}

	test("multi-source package collapses into one header with all members when expanded") {
		val members = listOf(
			sourceItem(id = "mihon:1", packageId = "pkg:multilang", packageName = "MultiLang"),
			sourceItem(id = "mihon:2", packageId = "pkg:multilang", packageName = "MultiLang"),
			sourceItem(id = "mihon:3", packageId = "pkg:multilang", packageName = "MultiLang"),
		)

		val rows = buildGroupedUnifiedSourceRows(members, emptySet())

		rows shouldHaveSize 4
		val header = rows[0].shouldBeInstanceOf<UnifiedSourceDisplayRow.PackageHeader>()
		header.packageId shouldBe "pkg:multilang"
		header.packageName shouldBe "MultiLang"
		header.sourceCount shouldBe 3
		header.collapsed shouldBe false
		header.members shouldBe members
		rows[1] shouldBe UnifiedSourceDisplayRow.SourceItem(members[0])
		rows[2] shouldBe UnifiedSourceDisplayRow.SourceItem(members[1])
		rows[3] shouldBe UnifiedSourceDisplayRow.SourceItem(members[2])
	}

	test("collapsed multi-source package hides members") {
		val members = listOf(
			sourceItem(id = "mihon:1", packageId = "pkg:multilang", packageName = "MultiLang"),
			sourceItem(id = "mihon:2", packageId = "pkg:multilang", packageName = "MultiLang"),
		)

		val rows = buildGroupedUnifiedSourceRows(members, setOf("pkg:multilang"))

		rows shouldHaveSize 1
		val header = rows.single().shouldBeInstanceOf<UnifiedSourceDisplayRow.PackageHeader>()
		header.collapsed shouldBe true
	}

	test("members appearing non-adjacent are pulled together at the first occurrence") {
		val a1 = sourceItem(id = "mihon:1", packageId = "pkg:multilang", packageName = "MultiLang")
		val single = sourceItem(id = "src-single")
		val a2 = sourceItem(id = "mihon:2", packageId = "pkg:multilang", packageName = "MultiLang")
		val b = sourceItem(id = "src-b")

		val rows = buildGroupedUnifiedSourceRows(listOf(a1, single, a2, b), emptySet())

		rows shouldHaveSize 5
		rows[0].shouldBeTypeOf<UnifiedSourceDisplayRow.PackageHeader>().members shouldBe listOf(a1, a2)
		rows[1] shouldBe UnifiedSourceDisplayRow.SourceItem(a1)
		rows[2] shouldBe UnifiedSourceDisplayRow.SourceItem(a2)
		rows[3] shouldBe UnifiedSourceDisplayRow.SourceItem(single)
		rows[4] shouldBe UnifiedSourceDisplayRow.SourceItem(b)
	}

	test("two multi-source packages each get their own header in first-occurrence order") {
		val a1 = sourceItem(id = "p1:1", packageId = "pkg:p1", packageName = "Pack One")
		val b1 = sourceItem(id = "p2:1", packageId = "pkg:p2", packageName = "Pack Two")
		val a2 = sourceItem(id = "p1:2", packageId = "pkg:p1", packageName = "Pack One")

		val rows = buildGroupedUnifiedSourceRows(listOf(a1, b1, a2), emptySet())

		rows shouldHaveSize 4
		rows[0].shouldBeInstanceOf<UnifiedSourceDisplayRow.PackageHeader>().packageId shouldBe "pkg:p1"
		rows[1] shouldBe UnifiedSourceDisplayRow.SourceItem(a1)
		rows[2] shouldBe UnifiedSourceDisplayRow.SourceItem(a2)
		// pkg:p2 has exactly one source, so it stays a flat single-source row
		rows[3] shouldBe UnifiedSourceDisplayRow.SourceItem(b1)
	}

	test("empty list yields empty rows") {
		buildGroupedUnifiedSourceRows(emptyList(), emptySet()) shouldHaveSize 0
	}
})

private class FakeSource(override val name: String) : ContentSource {
	override val locale: String = "en"
	override val contentType: ContentType = ContentType.MANGA
}

private fun sourceItem(
	id: String,
	packageId: String? = null,
	packageName: String? = null,
	kind: UnifiedSourceKind = UnifiedSourceKind.MIHON,
): UnifiedSourceItem {
	return UnifiedSourceItem(
		id = id,
		kind = kind,
		source = FakeSource(id),
		title = id,
		language = null,
		contentType = ContentType.MANGA,
		repositoryId = null,
		repositoryName = null,
		packageId = packageId,
		packageName = packageName,
		isEnabled = true,
		isPinned = false,
		isAvailable = true,
		isInstalled = true,
		isNsfw = false,
		isBroken = false,
	)
}
