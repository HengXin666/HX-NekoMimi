package com.hx.nekomimi.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.hx.nekomimi.data.entity.Chapter

/**
 * 文件扫描工具
 * 递归扫描目录下的 mp3 文件，并自动匹配同名字幕文件（SRT/ASS）
 */
object FileScanner {

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4s", "flac", "wav", "ogg", "aac", "wma")
    private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa")

    /**
     * 通过 SAF Uri 扫描书籍目录
     * @param context 上下文
     * @param treeUri 目录树 URI
     * @param bookId 书籍 ID
     * @return 扫描到的章节列表
     */
    fun scanFromUri(context: Context, treeUri: Uri, bookId: Long): List<Chapter> {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val chapters = mutableListOf<ChapterScanResult>()
        val subtitleMap = mutableMapOf<String, SubtitleInfo>()

        // 第一遍：收集所有音频和字幕文件
        scanDirectory(context, rootDoc, "", chapters, subtitleMap)

        // 第二遍：匹配字幕文件到章节
        return chapters.sortedWith(compareBy({ it.parentFolder }, { it.sortOrder }, { it.title }))
            .mapIndexed { index, result ->
                // 尝试匹配同名字幕文件
                val baseName = result.title.substringBeforeLast(".")
                val subtitleKey = "${result.parentFolder}/$baseName"
                val subtitle = subtitleMap[subtitleKey]

                Chapter(
                    bookId = bookId,
                    title = result.title.substringBeforeLast("."), // 去掉扩展名作为标题
                    fileUri = result.fileUri,
                    subtitleUri = subtitle?.uri,
                    parentFolder = result.parentFolder,
                    sortOrder = index
                )
            }
    }

    private fun scanDirectory(
        context: Context,
        dir: DocumentFile,
        currentPath: String,
        chapters: MutableList<ChapterScanResult>,
        subtitleMap: MutableMap<String, SubtitleInfo>
    ) {
        val files = dir.listFiles()

        // 按名称排序
        val sorted = files.sortedBy { it.name?.lowercase() ?: "" }

        for (file in sorted) {
            val name = file.name ?: continue

            if (file.isDirectory) {
                // 递归扫描子目录
                val subPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                scanDirectory(context, file, subPath, chapters, subtitleMap)
            } else {
                val ext = name.substringAfterLast(".", "").lowercase()
                val baseName = name.substringBeforeLast(".")
                val key = if (currentPath.isEmpty()) "/$baseName" else "$currentPath/$baseName"

                if (ext in AUDIO_EXTENSIONS) {
                    chapters.add(
                        ChapterScanResult(
                            title = name,
                            fileUri = file.uri.toString(),
                            parentFolder = currentPath,
                            sortOrder = chapters.size
                        )
                    )
                } else if (ext in SUBTITLE_EXTENSIONS) {
                    subtitleMap[key] = SubtitleInfo(
                        fileName = name,
                        uri = file.uri.toString()
                    )
                }
            }
        }
    }

    /**
     * 根据音频文件 URI 读取对应字幕文件内容
     */
    fun readSubtitleContent(context: Context, subtitleUri: String): String? {
        return try {
            val uri = Uri.parse(subtitleUri)
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 读取音频文件的 URI
     */
    fun getAudioUri(chapter: Chapter): Uri? {
        return chapter.fileUri?.let { Uri.parse(it) }
    }

    private data class ChapterScanResult(
        val title: String,
        val fileUri: String,
        val parentFolder: String,
        val sortOrder: Int
    )

    private data class SubtitleInfo(
        val fileName: String,
        val uri: String
    )
}
