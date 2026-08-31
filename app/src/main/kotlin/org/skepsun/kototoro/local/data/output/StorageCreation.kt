package org.skepsun.kototoro.local.data.output

import com.hippo.unifile.UniFile
import org.skepsun.kototoro.core.exceptions.StorageWriteException

/**
 * UniFile 的 `createFile` / `createDirectory` 在失败时返回 null 而不抛异常（SAF 授权失效、
 * 只读卷、空间不足、名称非法都会走这条路），而下游普遍是 `checkNotNull(...)` 这种无 message
 * 的断言 —— 结果用户只能看到 Kotlin intrinsic 的 "Required value was null."（issue #511）。
 * 所有创建点统一走这两个函数，把「在哪个根目录建什么失败了」保留下来。
 */
internal fun UniFile.createFileOrThrow(name: String): UniFile =
    findFile(name) ?: createFile(name) ?: throw StorageWriteException(
        rootUri = describeSelf(),
        targetName = name,
        targetIsDirectory = false,
    )

internal fun UniFile.createDirectoryOrThrow(name: String): UniFile =
    findFile(name) ?: createDirectory(name) ?: throw StorageWriteException(
        rootUri = describeSelf(),
        targetName = name,
        targetIsDirectory = true,
    )

/** 构造错误上下文时绝不能自己再抛一次，否则又把真实原因弄丢了。 */
private fun UniFile.describeSelf(): String? = runCatching { uri?.toString() }.getOrNull()
