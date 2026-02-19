package com.hx.nekomimi.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.hx.nekomimi.player.PlayerManager
import com.hx.nekomimi.data.repository.PlaybackRepository
import com.hx.nekomimi.ui.shelf.BookShelfScreen
import com.hx.nekomimi.ui.player.BookDetailScreen
import com.hx.nekomimi.ui.player.BookPlayerScreen

/**
 * 导航图
 * 书架(主页) → 书详情 → 播放页
 */
@Composable
fun NavGraph(
    navController: NavHostController,
    playerManager: PlayerManager,
    repository: PlaybackRepository
) {
    NavHost(
        navController = navController,
        startDestination = Screen.BookShelf.route
    ) {
        // 书架 (主界面)
        composable(Screen.BookShelf.route) {
            BookShelfScreen(
                repository = repository,
                onBookClick = { bookId ->
                    navController.navigate(Screen.BookDetail.createRoute(bookId))
                }
            )
        }

        // 书详情页
        composable(
            route = Screen.BookDetail.route,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getLong("bookId") ?: return@composable
            BookDetailScreen(
                bookId = bookId,
                repository = repository,
                playerManager = playerManager,
                onNavigateBack = { navController.popBackStack() },
                onPlayChapter = { bId, chapterIndex ->
                    navController.navigate(Screen.BookPlayer.createRoute(bId, chapterIndex))
                }
            )
        }

        // 播放页
        composable(
            route = Screen.BookPlayer.route,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("chapterIndex") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getLong("bookId") ?: return@composable
            val chapterIndex = backStackEntry.arguments?.getInt("chapterIndex") ?: return@composable
            BookPlayerScreen(
                bookId = bookId,
                chapterIndex = chapterIndex,
                repository = repository,
                playerManager = playerManager,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
