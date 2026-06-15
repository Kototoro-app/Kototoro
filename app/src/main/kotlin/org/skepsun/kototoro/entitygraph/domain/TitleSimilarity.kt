package org.skepsun.kototoro.entitygraph.domain

import org.skepsun.kototoro.parsers.util.levenshteinDistance
import java.text.Normalizer

private const val TITLE_SUBSET_MIN_COMMON_CHARS = 6
private const val JARO_WINKLER_PREFIX_LIMIT = 4
private const val JARO_WINKLER_PREFIX_SCALE = 0.1f
private val TITLE_PUNCTUATION_REGEX = Regex("[\\p{P}\\p{S}]+")
private val TITLE_WHITESPACE_REGEX = Regex("\\s+")
private val TITLE_BRACKETED_NOISE_REGEX = Regex(
	"(?i)[\\(（\\[【［｢「『].*?(补档|補檔|汉化|漢化|翻译|翻譯|中文|中国翻译|中國翻譯|dl版|digital).*?[\\)）\\]】］｣」』]",
)
private val TITLE_STANDALONE_NOISE_REGEX = Regex(
	"(?i)\\b(dl版|digital|digital colored comics|scanlation)\\b|补档|補檔|汉化组|漢化組|汉化|漢化|中国翻译|中國翻譯",
)
private val TRADITIONAL_TO_SIMPLIFIED_TITLE_CHARS = mapOf(
	'裡' to '里',
	'裏' to '里',
	'險' to '险',
	'東' to '东',
	'麼' to '么',
	'臺' to '台',
	'台' to '台',
	'灣' to '湾',
	'與' to '与',
	'門' to '门',
	'們' to '们',
	'個' to '个',
	'國' to '国',
	'學' to '学',
	'會' to '会',
	'體' to '体',
	'異' to '异',
	'戰' to '战',
	'鬥' to '斗',
	'龍' to '龙',
	'劍' to '剑',
	'魔' to '魔',
	'後' to '后',
	'宮' to '宫',
	'終' to '终',
	'補' to '补',
	'檔' to '档',
	'漢' to '汉',
	'譯' to '译',
	'聲' to '声',
	'戀' to '恋',
	'愛' to '爱',
	'內' to '内',
	'壞' to '坏',
	'念' to '念',
	'頭' to '头',
	'膽' to '胆',
	'黨' to '党',
	'裝' to '装',
	'噹' to '当',
	'達' to '达',
)

fun titleSimilarityScore(left: String, right: String): Float {
	if (left == right) return 1f
	val normalizedLeft = left.toSimilarityTitleForm()
	val normalizedRight = right.toSimilarityTitleForm()
	if (normalizedLeft.isNotBlank() && normalizedLeft == normalizedRight) return 1f
	val compactLeft = normalizedLeft.removeTitleSeparators()
	val compactRight = normalizedRight.removeTitleSeparators()
	if (compactLeft.isNotBlank() && compactLeft == compactRight) return 1f
	val leftTokens = normalizedLeft.toTitleTokens()
	val rightTokens = normalizedRight.toTitleTokens()
	val tokenKeyLeft = leftTokens.toSortedTitleTokenKey()
	val tokenKeyRight = rightTokens.toSortedTitleTokenKey()
	if (tokenKeyLeft.isNotBlank() && tokenKeyLeft == tokenKeyRight) return 1f
	return maxOf(
		levenshteinTitleSimilarity(normalizedLeft, normalizedRight),
		levenshteinTitleSimilarity(compactLeft, compactRight),
		levenshteinTitleSimilarity(tokenKeyLeft, tokenKeyRight),
		jaroWinklerTitleSimilarity(normalizedLeft, normalizedRight),
		jaroWinklerTitleSimilarity(compactLeft, compactRight),
		tokenSetTitleSimilarity(leftTokens, rightTokens),
		ngramJaccardTitleSimilarity(compactLeft, compactRight),
	)
}

fun titleBlockingKeys(value: String): Set<String> {
	val normalized = value.toSimilarityTitleForm()
	val compact = normalized.removeTitleSeparators()
	val tokens = normalized.toTitleTokens()
	return buildSet {
		tokens
			.filter { it.length >= 2 }
			.forEach { token ->
				add("t:$token")
				add("p:${token.take(3)}")
			}
		when {
			compact.length <= 2 && compact.isNotBlank() -> add("s:$compact")
			compact.length <= 5 -> add("p:${compact.take(2)}")
			else -> {
				add("p:${compact.take(3)}")
				compact.toCharacterNgrams(2).forEach { add("g:$it") }
				compact.toCharacterNgrams(3).forEach { add("h:$it") }
			}
		}
	}
}

private fun String.toSimilarityTitleForm(): String {
	return Normalizer.normalize(this, Normalizer.Form.NFKC)
		.lowercase()
		.map { char -> TRADITIONAL_TO_SIMPLIFIED_TITLE_CHARS[char] ?: char }
		.joinToString("")
		.replace(TITLE_BRACKETED_NOISE_REGEX, " ")
		.replace(TITLE_STANDALONE_NOISE_REGEX, " ")
		.replace(TITLE_PUNCTUATION_REGEX, " ")
		.replace(TITLE_WHITESPACE_REGEX, " ")
		.trim()
}

private fun levenshteinTitleSimilarity(left: String, right: String): Float {
	val maxLength = maxOf(left.length, right.length)
	if (maxLength == 0) return 0f
	return (1f - left.levenshteinDistance(right).toFloat() / maxLength.toFloat()).coerceIn(0f, 1f)
}

private fun String.removeTitleSeparators(): String {
	return replace(TITLE_WHITESPACE_REGEX, "")
}

private fun String.toTitleTokens(): List<String> {
	return split(TITLE_WHITESPACE_REGEX)
		.asSequence()
		.filter { it.isNotBlank() }
		.toList()
}

private fun List<String>.toSortedTitleTokenKey(): String {
	return asSequence()
		.sorted()
		.joinToString(" ")
}

private fun jaroWinklerTitleSimilarity(left: String, right: String): Float {
	if (left == right) return 1f
	if (left.isEmpty() || right.isEmpty()) return 0f
	val matchDistance = (maxOf(left.length, right.length) / 2 - 1).coerceAtLeast(0)
	val leftMatches = BooleanArray(left.length)
	val rightMatches = BooleanArray(right.length)
	var matches = 0
	left.indices.forEach { leftIndex ->
		val start = (leftIndex - matchDistance).coerceAtLeast(0)
		val end = (leftIndex + matchDistance + 1).coerceAtMost(right.length)
		for (rightIndex in start until end) {
			if (rightMatches[rightIndex] || left[leftIndex] != right[rightIndex]) {
				continue
			}
			leftMatches[leftIndex] = true
			rightMatches[rightIndex] = true
			matches++
			break
		}
	}
	if (matches == 0) return 0f
	var rightIndex = 0
	var transpositions = 0
	left.indices.forEach { leftIndex ->
		if (!leftMatches[leftIndex]) return@forEach
		while (!rightMatches[rightIndex]) {
			rightIndex++
		}
		if (left[leftIndex] != right[rightIndex]) {
			transpositions++
		}
		rightIndex++
	}
	val halfTranspositions = transpositions / 2f
	val jaro = (
		matches / left.length.toFloat() +
			matches / right.length.toFloat() +
			(matches - halfTranspositions) / matches.toFloat()
		) / 3f
	val prefixLength = left.zip(right)
		.takeWhile { (leftChar, rightChar) -> leftChar == rightChar }
		.take(JARO_WINKLER_PREFIX_LIMIT)
		.size
	return (jaro + prefixLength * JARO_WINKLER_PREFIX_SCALE * (1f - jaro)).coerceIn(0f, 1f)
}

private fun tokenSetTitleSimilarity(leftTokens: List<String>, rightTokens: List<String>): Float {
	if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0f
	val leftSet = leftTokens.toSet()
	val rightSet = rightTokens.toSet()
	val common = leftSet intersect rightSet
	if (common.isEmpty()) return 0f
	val union = leftSet union rightSet
	val jaccard = common.size / union.size.toFloat()
	val containment = common.size / minOf(leftSet.size, rightSet.size).toFloat()
	val leftChars = leftSet.sumOf { it.length }
	val rightChars = rightSet.sumOf { it.length }
	val commonChars = common.sumOf { it.length }
	val subsetScore = if (commonChars >= TITLE_SUBSET_MIN_COMMON_CHARS && commonChars == minOf(leftChars, rightChars)) {
		val lengthBalance = minOf(leftChars, rightChars) / maxOf(leftChars, rightChars).toFloat()
		0.88f + 0.08f * lengthBalance
	} else {
		0f
	}
	return maxOf(
		leftTokens.toSortedTitleTokenKey().let { leftKey ->
			levenshteinTitleSimilarity(leftKey, rightTokens.toSortedTitleTokenKey())
		},
		0.65f * jaccard + 0.35f * containment,
		subsetScore,
	).coerceIn(0f, 1f)
}

private fun ngramJaccardTitleSimilarity(left: String, right: String, size: Int = 2): Float {
	if (left.isBlank() || right.isBlank()) return 0f
	val leftGrams = left.toCharacterNgrams(size)
	val rightGrams = right.toCharacterNgrams(size)
	val union = leftGrams union rightGrams
	if (union.isEmpty()) return 0f
	return (leftGrams intersect rightGrams).size / union.size.toFloat()
}

private fun String.toCharacterNgrams(size: Int): Set<String> {
	if (length <= size) return setOf(this)
	return windowed(size).toSet()
}
