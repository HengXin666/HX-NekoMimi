package com.hx.nekomimi.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 有声书 (一个文件夹 = 一本书)
 * 用户导入的文件夹作为一本书的根目录
 */
@Entity(
    tableName = "book",
    indices = [Index(value = ["folderPath"], unique = true)]
)
data class Book(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 书的根文件夹绝对路径 */
    val folderPath: String,
    /** SAF 授权的 URI (用于访问隐藏文件夹) */
    val folderUri: String? = null,
    /** 书名 (默认为文件夹名，用户可编辑) */
    val title: String,
    /** 书的描述 (用户可编辑) */
    val description: String = "",
    /** 封面图片路径 (预留，暂不使用) */
    val coverPath: String? = null,
    /** 导入时间戳 */
    val importedAt: Long = System.currentTimeMillis(),
    /** 最近更新时间戳 (最近一次播放/编辑) */
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    /** 最近播放的文件路径 */
    val lastPlayedFilePath: String? = null,
    /** 最近播放的文件 SAF URI (用于精确定位 SAF 模式下的文件) */
    val lastPlayedFileUri: String? = null,
    /** 最近播放的文件位置 (毫秒) */
    val lastPlayedPositionMs: Long = 0,
    /** 最近播放的文件总时长 (毫秒) */
    val lastPlayedDurationMs: Long = 0,
    /** 最近播放的文件显示名称 */
    val lastPlayedDisplayName: String? = null
)
