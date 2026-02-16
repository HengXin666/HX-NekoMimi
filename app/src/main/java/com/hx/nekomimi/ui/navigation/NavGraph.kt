package com.hx.nekomimi.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.hx.nekomimi.ui.bookmark.BookmarkScreen
import com.hx.nekomimi.ui.home.MusicHomeScreen
import com.hx.nekomimi.ui.player.BookDetailScreen
import com.hx.nekomimi.ui.player.BookPlayerScreen
import com.hx.nekomimi.ui.player.MusicPlayerScreen
import com.hx.nekomimi.ui.shelf.BookShelfScreen

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.MusicHome.route
    ) {
        // ========== 音乐 Tab ==========
        composable(Screen.MusicHome.route) {
            MusicHomeScreen(
                onNavigateToPlayer = {
                    navController.navigate(Screen.MusicPlayer.route) {
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Screen.MusicPlayer.route) {
            MusicPlayerScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // ========== 听书 Tab ==========
        composable(Screen.BookShelf.route) {
            BookShelfScreen(
                onNavigateToBookDetail = { folderPath ->
                    navController.navigate(Screen.BookDetail.createRoute(folderPath)) {
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(
            route = Screen.BookDetail.route,
            arguments = listOf(
                navArgument("bookFolderPath") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("bookFolderPath") ?: ""
            val folderPath = java.net.URLDecoder.decode(encodedPath, "UTF-8")
            BookDetailScreen(
                folderPath = folderPath,
                onNavigateToPlayer = {
                    navController.navigate(Screen.BookPlayer.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToBookmarks = {
                    navController.navigate(Screen.Bookmarks.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        composable(Screen.BookPlayer.route) {
            BookPlayerScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // ========== 书签 ==========
        composable(Screen.Bookmarks.route) {
            BookmarkScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
