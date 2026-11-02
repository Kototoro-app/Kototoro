package org.skepsun.kototoro.desktop.compat

import java.nio.file.Path

/** The running JDK's launcher, for tests that need a fresh process: `java.exe` only on Windows. */
internal fun currentJavaExecutable(): String = Path.of(
    System.getProperty("java.home"),
    "bin",
    if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
).toString()
