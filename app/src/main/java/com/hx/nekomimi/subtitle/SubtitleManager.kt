package com.hx.nekomimi.subtitle

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.hx.nekomimi.subtitle.model.AssStyle
import com.hx.nekomimi.subtitle.model.SubtitleCue
import java.io.File
import java.io.InputStreamReader

/**
 * 字幕管理器
 * 自动查找音频文件对应的字幕文件并加载
 * 支持 SRT 和 ASS 格式
 */
object SubtitleManager {

    private const val TAG = "SubtitleManager"

    /** 支持的字幕扩展名 */
    private val subtitleExtensions = listOf("ass", "ssa", "srt")

    /**
     * 字幕加载结果
     */
    sealed class SubtitleResult {
        /** 无字幕 */
        data object None : SubtitleResult()
        /** SRT 字幕 */
        data class Srt(val cues: List<SubtitleCue>) : SubtitleResult()
        /** ASS 字幕 (含样式和特效) */
        data class Ass(
            val document: AssParser.AssDocument,
            val cues: List<SubtitleCue>,
            val styles: Map<String, AssStyle>,
            val rawContent: String = "" // ASS 文件原始内容，用于 libass 渲染
        ) : SubtitleResult()
    }

    /**
     * 为指定音频文件查找并加载字幕
     * 查找策略: 同目录下同名文件，优先 ASS > SRT
     *
     * @param audioFilePath 音频文件路径
     * @return 字幕加载结果
     */
    fun loadForAudio(audioFilePath: String): SubtitleResult {
        val audioFile = File(audioFilePath)
        val baseName = audioFile.nameWithoutExtension
        val dir = audioFile.parentFile

        Log.d(TAG, "loadForAudio: audioPath=$audioFilePath, baseName=$baseName, dir=${dir?.absolutePath}")

        if (dir == null) {
            Log.w(TAG, "loadForAudio: parent directory is null")
            return SubtitleResult.None
        }

        if (!dir.exists()) {
            Log.w(TAG, "loadForAudio: directory does not exist: ${dir.absolutePath}")
            return SubtitleResult.None
        }

        // 列出目录中所有字幕相关文件
        val allFiles = dir.listFiles()?.map { it.name } ?: emptyList()
        Log.d(TAG, "loadForAudio: files in dir: $allFiles")

        // 优先查找 ASS，再查找 SRT
        for (ext in subtitleExtensions) {
            val subtitleFile = File(dir, "$baseName.$ext")
            Log.d(TAG, "loadForAudio: checking ${subtitleFile.absolutePath}, exists=${subtitleFile.exists()}, isFile=${subtitleFile.isFile}")

            if (subtitleFile.exists() && subtitleFile.isFile) {
                Log.i(TAG, "loadForAudio: found subtitle file: ${subtitleFile.absolutePath}")
                return when (ext) {
                    "ass", "ssa" -> {
                        val rawContent = subtitleFile.readText()
                        val doc = AssParser.parse(rawContent)
                        Log.i(TAG, "loadForAudio: parsed ASS, cues=${doc.cues.size}, styles=${doc.styles.size}")
                        SubtitleResult.Ass(doc, doc.cues, doc.styles, rawContent)
                    }
                    "srt" -> {
                        val cues = SrtParser.parse(subtitleFile)
                        Log.i(TAG, "loadForAudio: parsed SRT, cues=${cues.size}")
                        SubtitleResult.Srt(cues)
                    }
                    else -> SubtitleResult.None
                }
            }
        }

        Log.w(TAG, "loadForAudio: no subtitle found for $baseName")
        return SubtitleResult.None
    }

    /**
     * 通过 URI 方式加载字幕 (支持隐藏文件夹)
     * 查找策略: 同目录下同名文件，优先 ASS > SRT
     *
     * @param context 上下文
     * @param folderUri 文件夹的 SAF URI
     * @param audioFileName 音频文件名 (不含路径)
     * @return 字幕加载结果
     */
    fun loadForAudioFromUri(context: Context, folderUri: Uri, audioFileName: String): SubtitleResult {
        val baseName = audioFileName.substringBeforeLast('.')
        val treeDoc = DocumentFile.fromTreeUri(context, folderUri)

        Log.d(TAG, "loadForAudioFromUri: folderUri=$folderUri, audioFileName=$audioFileName, baseName=$baseName")

        if (treeDoc == null) {
            Log.w(TAG, "loadForAudioFromUri: treeDoc is null")
            return SubtitleResult.None
        }

        // 列出文件夹中所有文件
        val allFiles = treeDoc.listFiles().map { it.name }
        Log.d(TAG, "loadForAudioFromUri: files in folder: $allFiles")

        // 在文件夹中查找字幕文件
        for (ext in subtitleExtensions) {
            val subtitleName = "$baseName.$ext"
            val subtitleDoc = treeDoc.findFile(subtitleName)
            Log.d(TAG, "loadForAudioFromUri: checking $subtitleName, found=${subtitleDoc != null}")

            if (subtitleDoc != null && subtitleDoc.isFile) {
                Log.i(TAG, "loadForAudioFromUri: found subtitle file: $subtitleName")
                return when (ext) {
                    "ass", "ssa" -> {
                        val rawContent = readUriContent(context, subtitleDoc.uri)
                        val doc = AssParser.parse(rawContent)
                        Log.i(TAG, "loadForAudioFromUri: parsed ASS, cues=${doc.cues.size}, styles=${doc.styles.size}")
                        SubtitleResult.Ass(doc, doc.cues, doc.styles, rawContent)
                    }
                    "srt" -> {
                        val content = readUriContent(context, subtitleDoc.uri)
                        val cues = SrtParser.parse(content)
                        Log.i(TAG, "loadForAudioFromUri: parsed SRT, cues=${cues.size}")
                        SubtitleResult.Srt(cues)
                    }
                    else -> SubtitleResult.None
                }
            }
        }

        Log.w(TAG, "loadForAudioFromUri: no subtitle found for $baseName")
        return SubtitleResult.None
    }

    /**
     * 从 URI 读取文件内容
     */
    private fun readUriContent(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            InputStreamReader(inputStream, "UTF-8").use { reader ->
                reader.readText()
            }
        } ?: ""
    }

    /**
     * 根据当前播放位置获取应显示的字幕行
     */
    fun getActiveCues(cues: List<SubtitleCue>, positionMs: Long): List<SubtitleCue> {
        return cues.filter { positionMs in it.startMs..it.endMs }
    }

    /**
     * 查找当前位置最近的字幕索引 (用于歌词列表滚动定位)
     * @return 当前高亮的字幕索引，-1 表示没有
     */
    fun findCurrentIndex(cues: List<SubtitleCue>, positionMs: Long): Int {
        if (cues.isEmpty()) return -1

        // 二分查找
        var low = 0
        var high = cues.size - 1
        var result = -1

        while (low <= high) {
            val mid = (low + high) / 2
            when {
                positionMs >= cues[mid].startMs && positionMs <= cues[mid].endMs -> return mid
                positionMs < cues[mid].startMs -> high = mid - 1
                else -> {
                    result = mid
                    low = mid + 1
                }
            }
        }

        return result
    }
}
