package org.skepsun.kototoro.backups.domain

import java.util.zip.ZipEntry

/** Zip based lookup of a section; the name based overload lives with the shared [BackupSection] in :core-backup. */
fun BackupSection.Companion.of(entry: ZipEntry): BackupSection? = of(entry.name)
