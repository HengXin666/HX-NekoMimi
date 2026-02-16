package com.hx.nekomimi.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 听书书签
 * 用户手动标记的位置，支持命名和回溯
 */
@Entity(
    tableName = "bookmark",
    indices = [Index(value = ["filePath", "positionMs"])]
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 音频文件绝对路径 */
    val filePath: String,
    /** 书签位置 (毫秒) */
    val positionMs: Long,
    /** 音频总时长 (毫秒) */
    val durationMs: Long,
    /** 书签名称 (用户可编辑，默认自动生成) */
    val label: String,
    /** 创建时间戳 */
    val createdAt: Long,
    /** 所属文件夹路径 */
    val folderPath: String,
    /** 文件显示名称 */
    val displayName: String
)
