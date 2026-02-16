package com.hx.nekomimi.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 导航路由定义
 *
 * 底部导航:
 *   音乐 Tab: MusicHome (文件夹浏览) / MusicPlayer (播放页+歌词)
 *   听书 Tab: BookShelf (书架) / BookDetail (书详情) / BookPlayer (听书播放+字幕)
 */
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    // ========== 底部导航 Tab ==========
    /** 音乐 Tab 入口 */
    data object MusicHome : Screen("music_home", "音乐", Icons.Filled.MusicNote)
    /** 听书 Tab 入口 */
    data object BookShelf : Screen("book_shelf", "听书", Icons.Filled.Headphones)

    // ========== 音乐子页面 ==========
    /** 音乐播放页 (歌词同步) */
    data object MusicPlayer : Screen("music_player", "正在播放", Icons.Filled.PlayCircle)

    // ========== 听书子页面 ==========
    /** 书详情页 (文件夹视图 + 书信息 + 记忆位置) */
    data object BookDetail : Screen("book_detail/{bookFolderPath}", "书详情", Icons.Filled.MenuBook) {
        fun createRoute(bookFolderPath: String): String =
            "book_detail/${java.net.URLEncoder.encode(bookFolderPath, "UTF-8")}"
    }
    /** 听书播放页 (字幕 + 记忆提示) */
    data object BookPlayer : Screen("book_player", "听书播放", Icons.Filled.Headphones)
    /** 书签管理页 */
    data object Bookmarks : Screen("bookmarks", "书签", Icons.Filled.Bookmark)
}

/** 底部导航栏的 Tab 列表 */
val bottomNavItems = listOf(
    Screen.MusicHome,
    Screen.BookShelf
)
