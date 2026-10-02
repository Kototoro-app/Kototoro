package org.skepsun.kototoro.parsers

/**
 * Annotate [ContentParser] implementation to mark this parser as broken instead of removing it
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Broken(

	/**
	 * Reason why this parser is broken
	 */
	val message: String = "",
)
