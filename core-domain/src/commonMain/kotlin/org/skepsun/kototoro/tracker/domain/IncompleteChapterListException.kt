package org.skepsun.kototoro.tracker.domain

/** A check got a chapter list too short to stand for the stored one, which therefore stays the baseline. */
class IncompleteChapterListException : IllegalStateException("The source returned an incomplete chapter list")
