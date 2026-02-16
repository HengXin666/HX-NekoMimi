package com.hx.nekomimi.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 音乐歌单 (一个文件夹 = 一个歌单)
 * 用户导入的文件夹作为歌单，自动扫描其中的音频文件
 */
@Entity(
    tableName = "music_playlist",
    indices = [Index(value = ["folderPath"], unique = true)]
)
data class MusicPlaylist(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 文件夹绝对路径 */
    val folderPath: String,
    /** 歌单名称 (默认为文件夹名，用户可编辑) */
    val name: String,
    /** 歌曲数量 (导入/刷新时统计) */
    val trackCount: Int = 0,
    /** 导入时间戳 */
    val importedAt: Long = System.currentTimeMillis(),
    /** 最近播放时间戳 */
    val lastPlayedAt: Long = 0
)
