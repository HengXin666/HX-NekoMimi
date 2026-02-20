package com.hx.nekomimi.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 书籍实体
 * @param id 自增主键
 * @param name 书籍名称
 * @param coverPath 封面图片路径（可选）
 * @param rootPath 书籍根目录路径（用于扫描 mp3 文件）
 * @param rootUri 书籍根目录 URI（SAF 方式）
 * @param createdAt 创建时间
 */
@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val coverPath: String? = null,
    val rootPath: String? = null,
    val rootUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
