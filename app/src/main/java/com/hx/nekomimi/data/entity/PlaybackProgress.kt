package com.hx.nekomimi.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 播放进度记录
 * @param id 自增主键
 * @param bookId 所属书籍 ID
 * @param chapterId 当前播放的章节 ID
 * @param positionMs 播放位置（毫秒）
 * @param updatedAt 最后更新时间
 */
@Entity(
    tableName = "playback_progress",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Chapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId", unique = true), Index("chapterId")]
)
data class PlaybackProgress(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val chapterId: Long,
    val positionMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis()
)
