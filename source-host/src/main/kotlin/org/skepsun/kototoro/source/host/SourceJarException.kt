package org.skepsun.kototoro.source.host

enum class SourceJarFailure {
    INVALID_ARCHIVE,
    INVALID_MANIFEST,
    UNSUPPORTED_LIBRARY,
    /** The manifest declares both a manga and a novel entry point; which runtime would own it is undecidable. */
    AMBIGUOUS_ECOSYSTEM,
    UNSUPPORTED_BYTECODE,
    HASH_MISMATCH,
    PACKAGE_MISMATCH,
    VERSION_MISMATCH,
    API_UNAVAILABLE,
    CONSTRUCTION_FAILED,
    NO_SOURCES,
    DUPLICATE_SOURCE,
    ALREADY_LOADED,
    CLOSED,
}

class SourceJarException(val failure: SourceJarFailure, cause: Throwable? = null) :
    Exception("Source JAR failure: ${failure.name}", cause)
