package org.skepsun.kototoro.core.exceptions

/**
 * 下载/导入时无法在存储根目录里创建文件或目录。
 *
 * UniFile 的 `createFile` / `createDirectory` 在失败时**返回 null 而不抛异常**（SAF 授权失效、
 * 只读卷、空间不足、名称非法都会走这条路），因此调用点必须显式判定 null 并抛出本异常，
 * 否则会退化成 Kotlin intrinsic 的 "Required value was null."，用户与日志都看不出真实原因。
 *
 * @param rootUri 存储根目录（SAF tree uri 或 file:// 路径），只进日志，不进 UI 文案
 * @param targetName 创建失败的文件/目录名
 */
class StorageWriteException(
    val rootUri: String?,
    val targetName: String?,
    val targetIsDirectory: Boolean,
    cause: Throwable? = null,
) : IllegalStateException(
    "Cannot create ${if (targetIsDirectory) "directory" else "file"} \"$targetName\" in $rootUri" +
        " (check the download directory grant, free space and whether the volume is read-only)",
    cause,
)
