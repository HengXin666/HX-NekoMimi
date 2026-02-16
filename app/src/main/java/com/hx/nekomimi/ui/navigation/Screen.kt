package com.hx.nekomimi.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 导航路由定义
 */
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Home : Screen("home", "主页", Icons.Filled.Home)
    data object MusicPlayer : Screen("music_player", "音乐", Icons.Filled.MusicNote)
    data object BookPlayer : Screen("book_player", "听书", Icons.Filled.Headphones)
    data object Bookmarks : Screen("bookmarks", "书签", Icons.Filled.Bookmark)
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.MusicPlayer,
    Screen.BookPlayer,
    Screen.Bookmarks
)
