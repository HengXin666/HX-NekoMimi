package com.hx.nekomimi.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.hx.nekomimi.ui.bookmark.BookmarkScreen
import com.hx.nekomimi.ui.home.HomeScreen
import com.hx.nekomimi.ui.player.BookPlayerScreen
import com.hx.nekomimi.ui.player.MusicPlayerScreen

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen()
        }
        composable(Screen.MusicPlayer.route) {
            MusicPlayerScreen()
        }
        composable(Screen.BookPlayer.route) {
            BookPlayerScreen()
        }
        composable(Screen.Bookmarks.route) {
            BookmarkScreen()
        }
    }
}
