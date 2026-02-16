package com.hx.nekomimi.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 播放位置记忆
 * 记录每个音频文件的播放位置，支持进程被杀后恢复
 */
@Entity(
    tableName = "playback_memory",
    indices = [Index(value = ["filePath"], unique = true)]
)
data class PlaybackMemory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 音频文件绝对路径 */
    val filePath: String,
    /** 播放位置 (毫秒) */
    val positionMs: Long,
    /** 音频总时长 (毫秒) */
    val durationMs: Long,
    /** 记忆时间戳 (System.currentTimeMillis) */
    val savedAt: Long,
    /** 所属文件夹路径 (用于分组) */
    val folderPath: String,
    /** 文件显示名称 */
    val displayName: String
)
