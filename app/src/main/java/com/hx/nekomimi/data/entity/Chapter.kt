package com.hx.nekomimi.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 章节实体（对应一个 mp3 文件）
 * @param id 自增主键
 * @param bookId 所属书籍 ID
 * @param title 章节标题（mp3 文件名）
 * @param filePath 音频文件绝对路径
 * @param fileUri 音频文件 URI（SAF 方式）
 * @param subtitlePath 字幕文件路径（SRT/ASS，可选）
 * @param subtitleUri 字幕文件 URI（SAF 方式，可选）
 * @param parentFolder 父文件夹路径（用于树形结构展示）
 * @param sortOrder 排序序号
 * @param durationMs 音频时长（毫秒）
 */
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class Chapter(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val title: String,
    val filePath: String? = null,
    val fileUri: String? = null,
    val subtitlePath: String? = null,
    val subtitleUri: String? = null,
    val parentFolder: String = "",
    val sortOrder: Int = 0,
    val durationMs: Long = 0
)
