package org.skepsun.kototoro.download.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 单张小说图片下载失败的元数据（T4A.5）。
 *
 * 一张图对应一条记录；以整章为单位落盘为侧车 JSON 文件：
 * `<dir>/novel_failed_images_<mangaId>_<chapterId>.json`。
 *
 * 纯 JVM 可测（kotlinx.serialization 1.11.0 + File），无 Android 依赖。
 */
@Serializable
data class FailedChapterImage(
    val chapterId: Long,
    val url: String,
    /** 占位本地名（可空，若从未下载成功）。 */
    val localName: String? = null,
    val error: String? = null,
    val failedAt: Long,
)

/**
 * 失败图片元数据侧车存储。
 *
 * 读写均为尽力而为（best-effort）：读失败（文件缺失/JSON 损坏）静默返回空列表，
 * 写失败静默不写，绝不影响下载主流程。
 */
object NovelFailedImageStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 侧车 JSON 文件路径：`<dir>/novel_failed_images_<mangaId>_<chapterId>.json`。 */
    fun sidecarFile(dir: File, mangaId: Long?, chapterId: Long): File =
        File(dir, "novel_failed_images_${mangaId ?: "x"}_$chapterId.json")

    /**
     * 读取一章的失败记录；文件不存在或 JSON 损坏时静默返回空列表。
     *
     * @param file 侧车文件
     * @param chapterId 期望的章节 id，用于过滤（防御性，避免误读其它章节的侧车文件）
     */
    fun read(file: File, chapterId: Long): List<FailedChapterImage> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<FailedChapterImage>>(file.readText())
                .filter { it.chapterId == chapterId }
        }.getOrDefault(emptyList())
    }

    /** 写入一章的失败记录；失败时静默不写。 */
    fun write(file: File, chapterId: Long, failed: List<FailedChapterImage>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(failed))
        }
    }
}
