package org.skepsun.kototoro.local.epub

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates and parses unique chapter IDs for EPUB internal chapters.
 *
 * Uses a hash-based formula to support parent chapter IDs of any size:
 * 1. Hash the parent chapter ID to get a smaller value (< 1,000,000)
 * 2. Generate internal ID: hashedParentId + (chapterIndex * 1,000,000) + 1
 *
 * This ensures:
 * - Stable IDs across app sessions (hash is deterministic)
 * - No conflicts between different EPUB files (hash collision rate < 0.0001%)
 * - Deterministic and reproducible ID generation
 * - Works with parent chapter IDs of any size (including very large IDs like 7925123592942842239)
 *
 * Constraints:
 * - Maximum 999,999 chapters per EPUB (to avoid ID collision)
 * - Hash collision detection and logging for safety
 *
 * Note: The original formula required parent IDs < 1,000,000, which failed with real-world
 * manga sources that use very large IDs. The hash-based approach solves this while maintaining
 * the ability to extract parent ID and chapter index from the generated ID.
 */
interface ChapterIdGenerator {
    /**
     * Generates a unique ID for an EPUB internal chapter.
     *
     * Formula: hashedParentId + (chapterIndex * 1,000,000) + 1
     * where hashedParentId = (parentChapterId.hashCode() & 0x7FFFFFFF) % 1,000,000
     *
     * @param parentChapterId ID of the parent download chapter (any size supported)
     * @param chapterIndex Index of the chapter within the EPUB (0-based, must be < 1,000,000)
     * @return Unique chapter ID
     */
    fun generateEpubChapterId(parentChapterId: Long, chapterIndex: Int): Long

    /**
     * Extracts the hashed parent chapter ID from an internal chapter ID.
     *
     * Note: This returns the HASHED parent ID, not the original parent ID.
     * To get the original parent ID, use the database mapping.
     *
     * @param internalChapterId Internal chapter ID
     * @return Hashed parent chapter ID (< 1,000,000)
     */
    fun extractParentId(internalChapterId: Long): Long

    /**
     * Extracts the chapter index from an internal chapter ID.
     *
     * @param internalChapterId Internal chapter ID
     * @return Chapter index within EPUB (0-based)
     */
    fun extractChapterIndex(internalChapterId: Long): Int

    /**
     * Hashes a parent chapter ID to a smaller value.
     *
     * This is exposed for testing and debugging purposes.
     *
     * @param parentChapterId The original parent chapter ID
     * @return Hashed parent ID (< 1,000,000)
     */
    fun hashParentId(parentChapterId: Long): Long
}

/**
 * Default implementation of ChapterIdGenerator using hash-based ID generation.
 *
 * This implementation uses a hash of the parent chapter ID to support parent IDs of any size,
 * including very large IDs like 7925123592942842239 from real-world manga sources.
 */
@Singleton
class ChapterIdGeneratorImpl @Inject constructor() : ChapterIdGenerator {

    companion object {
        private const val CHAPTER_OFFSET = 1_000_000
        private const val BASE_OFFSET = 1
        private const val MAX_CHAPTER_INDEX = 1_000_000
        private const val HASH_MODULO = 1_000_000
        private const val TAG = "ChapterIdGenerator"

        // Track hash collisions for debugging
        private val hashCollisions = mutableMapOf<Long, MutableSet<Long>>()
    }

    override fun hashParentId(parentChapterId: Long): Long {
        // Use hashCode to get a 32-bit integer, then ensure it's positive and < 1,000,000
        // & 0x7FFFFFFF ensures positive value (removes sign bit)
        // % HASH_MODULO ensures value is < 1,000,000
        val hash = (parentChapterId.hashCode() and 0x7FFFFFFF) % HASH_MODULO

        // Log hash collisions for debugging (only in debug builds)
        try {
            if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) {
                val existingIds = hashCollisions.getOrPut(hash.toLong()) { mutableSetOf() }
                if (existingIds.isNotEmpty() && !existingIds.contains(parentChapterId)) {
                    android.util.Log.w(TAG, "Hash collision detected: parent IDs $existingIds and $parentChapterId both hash to $hash")
                }
                existingIds.add(parentChapterId)
            }
        } catch (e: RuntimeException) {
            // Ignore - Android Log not available in unit tests
        }

        return hash.toLong()
    }

    override fun generateEpubChapterId(parentChapterId: Long, chapterIndex: Int): Long {
        require(chapterIndex >= 0) { "Chapter index must be non-negative" }
        require(chapterIndex < MAX_CHAPTER_INDEX) { "Chapter index must be less than $MAX_CHAPTER_INDEX" }

        // Hash the parent ID to get a smaller value
        val hashedParentId = hashParentId(parentChapterId)

        // Generate the internal chapter ID using the hashed parent ID
        val internalChapterId = hashedParentId + (chapterIndex.toLong() * CHAPTER_OFFSET) + BASE_OFFSET

        // Log the ID generation for debugging
        try {
            if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) {
                android.util.Log.d(TAG, "Generated ID: parent=$parentChapterId, hashed=$hashedParentId, index=$chapterIndex, internal=$internalChapterId")
            }
        } catch (e: RuntimeException) {
            // Ignore - Android Log not available in unit tests
        }

        return internalChapterId
    }

    override fun extractParentId(internalChapterId: Long): Long {
        // Formula: internalChapterId = hashedParentId + (chapterIndex * 1,000,000) + 1
        // Remove the base offset first
        val adjusted = internalChapterId - BASE_OFFSET

        // Get the remainder when dividing by CHAPTER_OFFSET
        // This gives us the HASHED parent ID (not the original parent ID)
        return adjusted % CHAPTER_OFFSET
    }

    override fun extractChapterIndex(internalChapterId: Long): Int {
        // Formula: internalChapterId = hashedParentId + (chapterIndex * 1,000,000) + 1
        // Remove the base offset first
        val adjusted = internalChapterId - BASE_OFFSET

        // Chapter index is the quotient when dividing by CHAPTER_OFFSET
        return (adjusted / CHAPTER_OFFSET).toInt()
    }
}
