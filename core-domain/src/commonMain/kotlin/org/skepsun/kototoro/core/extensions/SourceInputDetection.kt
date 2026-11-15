package org.skepsun.kototoro.core.extensions

/**
 * What one pasted address or picked file actually is.
 *
 * The add-source entry used to start with a list of nine extension kinds, so the user had to know whether the link
 * was a repository index, a TVBox/CMS catalogue or a Legado book list before pasting anything. These rules answer that
 * question from the address alone, and [sniffSourceConfigFormat] answers it from the fetched body when the address is
 * ambiguous — every `https://…/index.json` looks the same until you read it.
 */
enum class SourceInputIntent {
    /** A repository index that declares packages (`.pb`, `index.min.json`, …). */
    REPOSITORY_INDEX,

    /** A source catalogue: TVBox / apple CMS sites, Legado book sources, Noven/JS payloads. */
    SOURCE_CONFIG,

    /** A package file that has to be installed first: `.jar`, `.apk`, `.cs3`. */
    EXTENSION_FILE,

    /** A local `.json` holding a source catalogue. */
    SOURCE_CONFIG_FILE,

    /** An `http(s)` address ending in `.json` whose role is only known after fetching it. */
    JSON_ADDRESS,

    /** Nothing recognisable: the caller keeps its manual choice. */
    UNKNOWN,
}

/**
 * Recognises [raw] (a pasted address, a `tvbox://` link or a file path) without touching the network.
 *
 * The rules are deliberately narrow: a wrong guess is worse than no guess, because it sends the user down a flow that
 * fails later. Anything that is not clearly one of the shapes above comes back as [SourceInputIntent.UNKNOWN] or
 * [SourceInputIntent.JSON_ADDRESS] so the UI can offer the manual kinds instead.
 *
 * @param nameHint an optional display name, used only when [raw] is a bare local file name without an extension.
 */
fun detectSourceInput(raw: String, nameHint: String? = null): SourceInputIntent {
    val text = raw.trim()
    if (text.isEmpty()) return SourceInputIntent.UNKNOWN
    val lower = text.lowercase()
    val path = lower.substringBefore('?').substringBefore('#')

    // Custom schemes are unambiguous by themselves.
    when {
        lower.startsWith("tvbox://") -> return SourceInputIntent.SOURCE_CONFIG
        lower.startsWith("legado://") || lower.startsWith("booksource://") || lower.startsWith("yuedu://") -> {
            return SourceInputIntent.SOURCE_CONFIG
        }
    }

    val fileName = path.substringAfterLast('/').ifEmpty { nameHint?.trim()?.lowercase().orEmpty() }
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")

    return when {
        extension == "jar" || extension == "apk" || extension == "cs3" -> SourceInputIntent.EXTENSION_FILE
        extension == "pb" -> SourceInputIntent.REPOSITORY_INDEX
        extension == "json" -> {
            val looksLocal = !lower.startsWith("http://") && !lower.startsWith("https://")
            when {
                looksLocal -> SourceInputIntent.SOURCE_CONFIG_FILE
                isRepositoryIndexName(fileName) -> SourceInputIntent.REPOSITORY_INDEX
                isSourceConfigName(fileName) -> SourceInputIntent.SOURCE_CONFIG
                else -> SourceInputIntent.JSON_ADDRESS
            }
        }
        else -> SourceInputIntent.UNKNOWN
    }
}

/** What a fetched JSON body turns out to be, when its address alone could not say. */
enum class SourceConfigFormat {
    /** A repository index that publishes downloadable packages. */
    REPOSITORY_INDEX,

    /** TVBox / apple CMS sites, with `sites`/`lives` at the top or one site API object per entry. */
    TVBOX_CMS,

    /** Legado book sources, with `bookSourceUrl`/`ruleSearch` or a bare array of them. */
    LEGADO,

    /** Neither shape: the caller keeps its manual choice. */
    UNKNOWN,
}

/**
 * Decides which parser a fetched [json] body belongs to.
 *
 * Both a repository index and a source catalogue are plain JSON, so only their members tell them apart: the Android
 * repository formats publish `packages`, TVBox/CMS declares `sites`/`lives`/`parses`, and Legado spells its book
 * sources as `bookSourceUrl`/`ruleSearch`. A body that matches none of them is [SourceConfigFormat.UNKNOWN] rather
 * than a guess, because the wrong parser reports an unhelpful failure much later.
 *
 * @param knownPackagesKey the package array key of the caller's own repository format, so a format that spells it
 * differently keeps working.
 */
fun sniffSourceConfigFormat(json: String, knownPackagesKey: String = "packages"): SourceConfigFormat {
    val trimmed = json.trimStart()
    if (trimmed.isEmpty()) return SourceConfigFormat.UNKNOWN
    if (trimmed.startsWith("[")) {
        // A bare array is a list of entries: Legado subscriptions are pasted that way, TVBox site lists too.
        val lower = json.lowercase()
        return when {
            "booksourceurl" in lower || "rulesearch" in lower -> SourceConfigFormat.LEGADO
            "\"api\"" in lower && ("\"key\"" in lower || "searchable" in lower) -> SourceConfigFormat.TVBOX_CMS
            else -> SourceConfigFormat.UNKNOWN
        }
    }
    val keys = topLevelKeysOf(json)
    if (keys.isEmpty()) return SourceConfigFormat.UNKNOWN
    return when {
        knownPackagesKey.lowercase() in keys -> SourceConfigFormat.REPOSITORY_INDEX
        keys.any { it in REPOSITORY_ONLY_KEYS } -> SourceConfigFormat.REPOSITORY_INDEX
        keys.any { it in LEGADO_KEYS } -> SourceConfigFormat.LEGADO
        keys.any { it in TVBOX_KEYS } -> SourceConfigFormat.TVBOX_CMS
        else -> SourceConfigFormat.UNKNOWN
    }
}

private val REPOSITORY_ONLY_KEYS = setOf("packages", "repo_url", "repourl", "manifest_version")

private val LEGADO_KEYS = setOf("booksource", "booksourceurl", "rulesearch", "ruletoc", "bookurl")

private val TVBOX_KEYS = setOf("sites", "lives", "parses", "lives_url")

private fun isRepositoryIndexName(fileName: String): Boolean =
    fileName in setOf("index.json", "index.min.json", "repo.json", "repository.json", "plugins.json")

private fun isSourceConfigName(fileName: String): Boolean =
    fileName in setOf("sites.json", "sources.json", "source.json", "tvbox.json", "cms.json", "booksources.json")

/**
 * Top-level members of [json] as lowercase names, or an empty set when it is not a JSON object.
 *
 * This reads the text instead of parsing it so the check stays dependency-free and cheap enough to run on a paste:
 * a quote-delimited name followed by a colon while the nesting depth is one. Nested objects, strings containing
 * braces and arrays are skipped, which is enough to separate the shapes above and never throws on malformed input.
 */
internal fun topLevelKeysOf(json: String): Set<String> {
    val keys = LinkedHashSet<String>()
    var depth = 0
    var inString = false
    var escaped = false
    var keyCandidate = false
    val current = StringBuilder()

    for (c in json) {
        if (inString) {
            when {
                escaped -> {
                    escaped = false
                    current.append(c)
                }
                c == '\\' -> escaped = true
                c == '"' -> {
                    inString = false
                    if (keyCandidate) keys += current.toString().lowercase()
                    keyCandidate = false
                }
                else -> current.append(c)
            }
            continue
        }
        when (c) {
            '"' -> {
                inString = true
                current.clear()
            }
            '{' -> {
                depth++
                // A name directly inside the outermost object is one of its keys; deeper ones are nested fields.
                keyCandidate = depth == 1
            }
            '[' -> {
                depth++
                keyCandidate = false
            }
            '}', ']' -> {
                depth--
                keyCandidate = false
            }
            ':' -> keyCandidate = false
            ',' -> keyCandidate = depth == 1
            else -> if (!c.isWhitespace()) keyCandidate = false
        }
    }
    return keys
}

/**
 * How many entries a top-level array such as `packages` or `sites` declares, so the add flow can say what it is about
 * to import before importing it.
 *
 * Reads the text rather than parsing it: the scan counts the entries of the first array that belongs to [key] at the
 * top level, and returns 0 when the key is missing, is not an array, or the text is not a JSON object. It never throws
 * on malformed input — a preview is a convenience, not a validation step.
 */
fun countSourceEntries(json: String, key: String = "packages"): Int {
    val wanted = key.lowercase()
    var depth = 0
    var inString = false
    var escaped = false
    var keyCandidate = false
    var current = StringBuilder()
    var sawKey = false
    var arrayDepth = -1
    var count = 0

    for (c in json) {
        if (inString) {
            when {
                escaped -> {
                    escaped = false
                    current.append(c)
                }
                c == '\\' -> escaped = true
                c == '"' -> {
                    inString = false
                    if (keyCandidate && current.toString().lowercase() == wanted) sawKey = true
                    keyCandidate = false
                }
                else -> current.append(c)
            }
            continue
        }
        if (arrayDepth >= 0) {
            when (c) {
                '"' -> {
                    inString = true
                    current.clear()
                }
                '{' -> {
                    depth++
                    if (depth == arrayDepth + 1) count++
                }
                '[' -> depth++
                ']', '}' -> {
                    if (depth == arrayDepth) return count
                    depth--
                }
                else -> Unit
            }
            continue
        }
        when (c) {
            '"' -> {
                inString = true
                current.clear()
            }
            '{' -> {
                depth++
                keyCandidate = depth == 1
            }
            '[' -> {
                depth++
                if (sawKey) {
                    arrayDepth = depth
                    sawKey = false
                }
                keyCandidate = false
            }
            '}' -> depth--
            ':' -> keyCandidate = false
            ',' -> keyCandidate = depth == 1
            else -> if (!c.isWhitespace()) keyCandidate = false
        }
    }
    return if (arrayDepth >= 0) count else 0
}
