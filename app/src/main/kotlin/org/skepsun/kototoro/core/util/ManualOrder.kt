package org.skepsun.kototoro.core.util

/** Reorders only the requested members; hidden and concurrently added members keep their slots. */
fun <T> mergeManualOrder(current: List<T>, requested: List<T>): List<T> {
    val currentIds = current.toHashSet()
    val ordered = requested.distinct().filter { it in currentIds }
    val members = ordered.toHashSet()
    val iterator = ordered.iterator()
    return current.map { if (it in members) iterator.next() else it }
}
