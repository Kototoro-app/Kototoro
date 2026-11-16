package org.skepsun.kototoro.suggestions.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.zip.ZipFile

/**
 * Measures a way of scoring candidates against what a reader actually did, with nothing but their own library.
 *
 * The library is cut into folds. Each in turn is hidden, the taste is worked out from the rest, and the hidden works
 * are scored as if they were candidates. A good scorer puts the ones the reader went on to read above the ones they
 * opened and left: the result is how often it does, over every such pair (the AUC; 0.5 is a coin toss).
 *
 * A library only holds what the reader chose to open, so this says how well a scorer tells a hit from a miss among
 * those, not how it does on a source's whole catalogue.
 */
internal object SuggestionHoldout {

	enum class Outcome { ENGAGED, DROPPED, OTHER }

	/** Scores [hidden] works for a reader known only by [rest]. */
	fun interface Scorer {
		fun score(rest: List<LibraryWork>, hidden: List<LibraryWork>, now: Long): List<Double>
	}

	class Result(val auc: Double, val engaged: Int, val dropped: Int, val pairs: Long)

	private const val ENGAGED_PERCENT = 0.5f
	private const val DROPPED_PERCENT = 0.1f
	private const val DROPPED_AFTER_MS = 21L * 24 * 60 * 60 * 1000

	fun outcomeOf(work: LibraryWork, now: Long): Outcome {
		val percent = work.percent ?: return Outcome.OTHER
		return when {
			percent >= ENGAGED_PERCENT -> Outcome.ENGAGED
			percent <= DROPPED_PERCENT && !work.isFavourite && work.lastReadAt > 0L &&
				now - work.lastReadAt > DROPPED_AFTER_MS -> Outcome.DROPPED
			else -> Outcome.OTHER
		}
	}

	fun evaluate(library: List<LibraryWork>, now: Long, folds: Int = 5, scorer: Scorer): Result {
		var wins = 0.0
		var pairs = 0L
		var engaged = 0
		var dropped = 0
		for (fold in 0 until folds) {
			val hidden = library.filterIndexed { index, _ -> index % folds == fold }
			val rest = library.filterIndexed { index, _ -> index % folds != fold }
			val scores = scorer.score(rest, hidden, now)
			val hits = ArrayList<Double>()
			val misses = ArrayList<Double>()
			hidden.forEachIndexed { index, work ->
				when (outcomeOf(work, now)) {
					Outcome.ENGAGED -> hits += scores[index]
					Outcome.DROPPED -> misses += scores[index]
					Outcome.OTHER -> Unit
				}
			}
			engaged += hits.size
			dropped += misses.size
			for (hit in hits) for (miss in misses) {
				wins += if (hit > miss) 1.0 else if (hit == miss) 0.5 else 0.0
				pairs++
			}
		}
		return Result(if (pairs == 0L) Double.NaN else wins / pairs, engaged, dropped, pairs)
	}

	/** What suggestions were built from before: the commonest tags of the last few works, by position. */
	val Legacy = Scorer { rest, hidden, _ ->
		val seed = rest.filter { it.lastReadAt > 0L }.take(20) + rest.filter { it.isFavourite }.take(20)
		val tags = suggestionSeedTags(emptyList(), seed.distinct().flatMap { it.tags })
		hidden.map { suggestionRelevance(it.tags, tags, ::suggestionTagsMatch).toDouble() }
	}

	/** The taste profile, every tag counting the same. */
	val Profile = Scorer { rest, hidden, now ->
		val profile = SuggestionProfile.of(rest, now)
		hidden.map { profile.affinity(suggestionTagKeys(it.tags)) }
	}

	/** The taste profile, each tag counted by how rare it is among the candidates: what [rankSuggestions] does. */
	val ProfileWithRarity = Scorer { rest, hidden, now ->
		val profile = SuggestionProfile.of(rest, now)
		val keys = hidden.map { suggestionTagKeys(it.tags) }
		val rarity = TagRarity.of(keys)
		keys.map { profile.affinity(it, rarity) }
	}

	/**
	 * A Kototoro backup (`*.bk.zip`) as a library, most recently read first, and the time it was taken: the latest
	 * reading in it, so that "left for three weeks" means what it meant then.
	 */
	fun readBackup(file: File): Pair<List<LibraryWork>, Long> = ZipFile(file).use { zip ->
		fun section(name: String) = zip.getEntry(name)?.let { entry ->
			Json.parseToJsonElement(zip.getInputStream(entry).bufferedReader().readText()).jsonArray.map { it.jsonObject }
		}.orEmpty()

		fun JsonObject.long(key: String) = get(key)?.jsonPrimitive?.longOrNull ?: 0L
		fun JsonObject.work(isFavourite: Boolean, history: JsonObject?): LibraryWork {
			val manga = getValue("manga").jsonObject
			return LibraryWork(
				titles = listOf(manga.getValue("title").jsonPrimitive.content) +
					manga["alt_title"]?.jsonPrimitive?.takeIf { it.isString }?.content?.split('\n').orEmpty(),
				tags = manga["tags"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("title").jsonPrimitive.content },
				isFavourite = isFavourite,
				percent = history?.get("percent")?.jsonPrimitive?.floatOrNull?.takeIf { it in 0f..1f },
				chapters = history?.get("chapters")?.jsonPrimitive?.intOrNull ?: 0,
				lastReadAt = history?.long("updated_at") ?: 0L,
			)
		}

		val history = section("history").filter { it.long("deleted_at") == 0L }.sortedByDescending { it.long("updated_at") }
		val favourites = section("favourites").filter { it.long("deleted_at") == 0L }.distinctBy { it.long("manga_id") }
		val saved = favourites.mapTo(HashSet()) { it.long("manga_id") }
		val read = history.mapTo(HashSet()) { it.long("manga_id") }
		val library = history.map { it.work(it.long("manga_id") in saved, it) } +
			favourites.filterNot { it.long("manga_id") in read }.map { it.work(true, null) }
		library to (history.firstOrNull()?.long("updated_at") ?: 0L)
	}
}
