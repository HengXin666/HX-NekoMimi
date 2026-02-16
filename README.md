
# 🐱 HX-NekoMimi

**NekoMimi** (猫耳) 是一款 Android 本地音频播放器，专注于**音乐播放**与**有声书收听**两大场景，支持歌词/字幕显示、播放位置记忆、书签标记等功能。

> 纯本地、无广告、无联网，你的音频，你做主。
> 🌸 粉色猫耳主题，温柔可爱~

---

## ✨ 功能特性

### 🎵 音乐播放
- **歌单管理**：导入文件夹创建歌单，支持多个歌单管理
- **歌曲元信息**：自动读取封面、歌名、歌手、专辑、时长等元信息
- **歌单详情**：显示歌曲列表，包含封面缩略图、标题、歌手、时长
- 自动按文件名排序生成播放列表
- 上一曲 / 下一曲 / 暂停 / 拖动进度条
- **播放模式切换**：顺序播放 / 随机播放 / 单曲循环
- **歌词同步显示**：自动加载同名字幕文件，支持 **SRT** 和 **ASS/SSA** 格式
- ASS 字幕特效渲染：颜色、加粗、斜体、字号、淡入淡出、描边、阴影
- 歌词列表自动滚动到当前行，高亮显示，带渐变遮罩效果
- **文件信息查看**：播放页可查看歌曲文件路径、大小等信息，支持跳转到外部文件管理器
- **迷你播放器**：底部导航栏上方常驻迷你播放器，显示封面、歌名、歌手，支持播放/暂停和切歌
- 支持格式：`MP3` `WAV` `M4A` `OGG` `FLAC` `AAC` `WMA` `OPUS` `APE` `ALAC` `M4S`
- 视频文件也可播放音频轨道：`MP4` `MKV` `WEBM` `AVI` `MOV` `TS` `3GP`

### 📖 有声书收听
- **书架管理**：导入文件夹作为一本书，支持按导入日期 / 最近更新排序
- **书详情页**：显示书的元信息（可编辑书名、描述），文件夹视图浏览章节
- **子文件夹支持**：一本书（根文件夹）内可包含多个子文件夹，按目录结构排列
- **播放位置自动记忆**：每 3 秒保存一次，双保险存储（Room + SharedPreferences）
- **5 分钟定时记忆**：听书模式下每播放 5 分钟自动保存一次，UI 显示 "正在保存位置..." → "✓ 已保存"
- **书签系统**：手动标记位置，支持命名、回溯、删除
- **字幕显示**：听书页同样支持 SRT / ASS 字幕同步显示
- **继续收听**：打开书详情页即可看到上次记忆的播放文件和位置，一键继续

### 📂 文件管理
- 通过系统文件选择器 (SAF) 选取音频文件夹
- 歌单模式管理导入的文件夹，支持删除歌单（不删除实际文件）
- 音频格式：`MP3` `WAV` `M4A` `OGG` `FLAC` `AAC` `WMA` `OPUS` `APE` `ALAC` `M4S`
- 视频格式（播放音轨）：`MP4` `MKV` `WEBM` `AVI` `MOV` `TS` `3GP`
- B站缓存格式：`M4S`（DASH 分段音视频）

### 🔔 系统集成
- 前台服务 + 通知栏播放控制（含封面、歌名、歌手）
- 支持蓝牙耳机/车机媒体按键
- **锁屏界面播放控制**：锁屏时显示歌曲封面和播放控制按钮
- **系统导航栏媒体控制**：Android 11+ 系统媒体控制面板集成
- 锁屏/息屏继续播放
- 系统媒体中心集成 (Media3 Session)
- 点击通知栏可直接返回应用

---

## 🏗️ 技术架构

### 技术栈

| 类别 | 技术 |
|------|------|
| **语言** | Kotlin |
| **UI 框架** | Jetpack Compose + Material 3 |
| **播放引擎** | Media3 (ExoPlayer) 1.5.1 |
| **依赖注入** | Hilt (Dagger) 2.53.1 |
| **数据库** | Room 2.6.1 |
| **导航** | Navigation Compose 2.8.5 |
| **异步** | Kotlin Coroutines + Flow |
| **本地存储** | DataStore / SharedPreferences |
| **最低支持** | Android 8.0 (API 26) |
| **目标版本** | Android 15 (API 35) |

### 项目结构

```
com.hx.nekomimi/
├── MainActivity.kt              # 应用入口，权限请求，底部导航，迷你播放器
├── NekoMimiApp.kt               # Application，Hilt 入口
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt       # Room 数据库定义 (v3)
│   │   ├── dao/
│   │   │   ├── BookDao.kt       # 有声书数据访问
│   │   │   ├── BookmarkDao.kt   # 书签数据访问
│   │   │   ├── MusicPlaylistDao.kt  # 音乐歌单数据访问
│   │   │   └── PlaybackMemoryDao.kt  # 播放记忆数据访问
│   │   └── entity/
│   │       ├── Book.kt          # 有声书实体 (文件夹=一本书)
│   │       ├── Bookmark.kt      # 书签实体
│   │       ├── MusicPlaylist.kt # 音乐歌单实体 (文件夹=一个歌单)
│   │       └── PlaybackMemory.kt # 播放记忆实体
│   └── repository/
│       └── PlaybackRepository.kt # 数据仓库 (Room + SP 双写 + Book/歌单 管理)
├── di/
│   └── AppModule.kt             # Hilt 依赖提供模块
├── player/
│   ├── PlayerManager.kt         # 播放器核心管理器 (歌单播放/元信息/播放模式/格式支持)
│   └── PlaybackService.kt       # 前台 MediaSessionService (通知栏/锁屏/导航栏)
├── subtitle/
│   ├── SubtitleManager.kt       # 字幕管理器 (自动查找加载)
│   ├── AssParser.kt             # ASS/SSA 字幕解析器
│   ├── SrtParser.kt             # SRT 字幕解析器
│   └── model/
│       ├── AssStyle.kt          # ASS 样式模型
│       └── SubtitleCue.kt       # 字幕行模型 (含特效)
└── ui/
    ├── theme/
    │   └── Theme.kt             # 🌸 粉色猫耳主题
    ├── navigation/
    │   ├── Screen.kt            # 路由定义
    │   └── NavGraph.kt          # 导航图
    ├── home/
    │   └── HomeScreen.kt        # 🎵 音乐主页 (歌单列表+歌曲元信息)
    ├── shelf/
    │   └── BookShelfScreen.kt   # 📚 书架 (有声书列表/排序)
    ├── player/
    │   ├── MusicPlayerScreen.kt # 🎵 音乐播放页 (封面+歌词+文件信息)
    │   ├── BookDetailScreen.kt  # 📖 书详情页 (信息编辑+章节列表)
    │   └── BookPlayerScreen.kt  # 🎧 听书播放页 (字幕+记忆提示)
    └── bookmark/
        └── BookmarkScreen.kt    # 全部书签管理页
```

### 页面导航

```
┌────────────────────────────────┐
│     迷你播放器 (全局底部)        │
├──────────────────┬─────────────┤
│    🎵 音乐 Tab   │  📚 听书 Tab │
│                  │             │
│  歌单列表 ──→    │ BookShelf ──→│
│  歌单详情 ──→    │ BookDetail──→│
│  MusicPlayer     │ BookPlayer  │
│ (歌单→歌曲→播放) │(书架→详情→播放)│
└──────────────────┴─────────────┘
```

底部导航栏包含两个 Tab：
- **🎵 音乐** — 歌单列表 → 歌单详情（封面+歌名+歌手+时长） → 音乐播放器 + 歌词同步 + 文件信息
- **📚 听书** — 书架 → 书详情（编辑/章节/记忆） → 听书播放 + 字幕 + 书签

全局迷你播放器悬浮于底部导航栏上方，随时控制播放。

---

## 🚀 构建与运行

### 环境要求

- Android Studio Ladybug 或更高版本
- JDK 17
- Gradle 8.7+
- Android SDK 35

### 构建步骤

```bash
# 克隆项目
git clone https://github.com/HengXin666/HX-NekoMimi.git

# 使用 Android Studio 打开项目，等待 Gradle 同步完成

# 或使用命令行构建
./gradlew assembleDebug
```

### 安装到设备

```bash
./gradlew installDebug
```

---

## 📋 权限说明

| 权限 | 用途 | 说明 |
|------|------|------|
| `READ_EXTERNAL_STORAGE` | 读取音频文件 | Android 12 及以下 |
| `READ_MEDIA_AUDIO` | 读取音频文件 | Android 13+ |
| `FOREGROUND_SERVICE` | 后台播放 | 前台服务 |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 媒体播放服务 | 通知栏控制 |
| `POST_NOTIFICATIONS` | 播放通知 | Android 13+ |
| `WAKE_LOCK` | 息屏播放 | 保持 CPU 唤醒 |

---

## 📝 使用说明

### 音乐模式
1. 切换到**音乐** Tab，点击右上角 ＋ 按钮导入音乐文件夹创建歌单
2. 歌单列表中显示每个歌单的名称和歌曲数量
3. 点击歌单进入歌单详情，查看歌曲列表（含封面、歌名、歌手、时长）
4. 点击歌曲即可开始播放，自动跳转到播放页
5. 播放页显示封面和歌词（需同目录下存在同名 `.srt` 或 `.ass` 文件）
6. 点击播放页右上角 ℹ️ 按钮查看文件信息，可跳转到外部文件管理器查看文件位置
7. 点击播放控制栏左侧按钮切换播放模式：顺序 → 随机 → 单曲循环
8. 迷你播放器始终显示在底部导航栏上方，支持播放/暂停和切歌

### 听书模式
1. 切换到**听书** Tab，点击右上角 ＋ 导入有声书文件夹
2. 书架中点击书进入详情页，查看/编辑书名和描述
3. 在详情页浏览章节文件夹，点击音频文件开始播放
4. 播放页自动每 5 分钟记忆位置，显示 "正在保存位置..." → "✓ 已保存"
5. 手动点击 💾 按钮可随时保存位置
6. 下次打开同一本书时，详情页显示上次记忆位置，一键继续

---

## 📄 License

本项目仅供学习交流使用。
