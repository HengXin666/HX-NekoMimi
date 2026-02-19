package com.hx.nekomimi.ui.navigation

/**
 * 路由定义
 * 3 个页面: 书架(主页) → 书详情 → 播放页
 */
sealed class Screen(val route: String) {
    /** 主界面: 书架 (书籍卡片列表) */
    data object BookShelf : Screen("book_shelf")

    /** 书详情页: 章节列表 + 记忆位置 */
    data object BookDetail : Screen("book_detail/{bookId}") {
        fun createRoute(bookId: Long) = "book_detail/$bookId"
    }

    /** 播放页: 字幕 + 进度条 + 控制按钮 */
    data object BookPlayer : Screen("book_player/{bookId}/{chapterIndex}") {
        fun createRoute(bookId: Long, chapterIndex: Int) = "book_player/$bookId/$chapterIndex"
    }
}
