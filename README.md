
# 🐱 HX-NekoMimi

**NekoMimi** (猫耳) 是一款 Android 本地音频播放器，专注于**音乐播放**与**有声书收听**两大场景，支持歌词/字幕显示、播放位置记忆、书签标记等功能。

> 纯本地、无广告、无联网，你的音频，你做主。
> 🌸 粉色猫耳主题，温柔可爱~

---

## ✨ 功能特性

### 🎵 音乐播放
- 文件夹浏览模式，选择文件夹即可加载全部音频
- 自动按文件名排序生成播放列表
- 上一曲 / 下一曲 / 暂停 / 拖动进度条
- **播放模式切换**：顺序播放 / 随机播放 / 单曲循环
- **歌词同步显示**：自动加载同名字幕文件，支持 **SRT** 和 **ASS/SSA** 格式
- ASS 字幕特效渲染：颜色、加粗、斜体、字号、淡入淡出、描边、阴影
- 歌词列表自动滚动到当前行，高亮显示，带渐变遮罩效果
- 支持格式：`MP3` `WAV` `M4A` `OGG` `FLAC` `AAC` `WMA` `OPUS` `APE` `ALAC`
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
- 支持子文件夹浏览，显示每个子文件夹的音频数量
- 支持返回上级目录
- 音频格式：`MP3` `WAV` `M4A` `OGG` `FLAC` `AAC` `WMA` `OPUS` `APE` `ALAC`
- 视频格式（播放音轨）：`MP4` `MKV` `WEBM` `AVI` `MOV` `TS` `3GP`

### 🔔 系统集成
- 前台服务 + 通知栏播放控制
- 支持蓝牙耳机/车机媒体按键
- 锁屏/息屏继续播放
- 系统媒体中心集成 (Media3 Session)

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
├── MainActivity.kt              # 应用入口，权限请求，底部导航 (音乐/听书)
├── NekoMimiApp.kt               # Application，Hilt 入口
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt       # Room 数据库定义 (v2)
│   │   ├── dao/
│   │   │   ├── BookDao.kt       # 有声书数据访问
│   │   │   ├── BookmarkDao.kt   # 书签数据访问
│   │   │   └── PlaybackMemoryDao.kt  # 播放记忆数据访问
│   │   └── entity/
│   │       ├── Book.kt          # 有声书实体 (文件夹=一本书)
│   │       ├── Bookmark.kt      # 书签实体
│   │       └── PlaybackMemory.kt # 播放记忆实体
│   └── repository/
│       └── PlaybackRepository.kt # 数据仓库 (Room + SP 双写 + Book 管理)
├── di/
│   └── AppModule.kt             # Hilt 依赖提供模块
├── player/
│   ├── PlayerManager.kt         # 播放器核心管理器 (播放模式/听书记忆/格式支持)
│   └── PlaybackService.kt       # 前台 MediaSessionService
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
    │   └── HomeScreen.kt        # 音乐主页 (文件夹浏览)
    ├── shelf/
    │   └── BookShelfScreen.kt   # 📚 书架 (有声书列表/排序)
    ├── player/
    │   ├── MusicPlayerScreen.kt # 🎵 音乐播放页 (歌词+播放模式)
    │   ├── BookDetailScreen.kt  # 📖 书详情页 (信息编辑+章节列表)
    │   └── BookPlayerScreen.kt  # 🎧 听书播放页 (字幕+记忆提示)
    └── bookmark/
        └── BookmarkScreen.kt    # 全部书签管理页
```

### 页面导航

```
┌──────────────────┬──────────────────┐
│    🎵 音乐 Tab   │    📚 听书 Tab   │
│                  │                  │
│  MusicHome ──→   │  BookShelf ──→   │
│  MusicPlayer     │  BookDetail ──→  │
│  (文件夹+播放)    │  BookPlayer      │
│                  │  (书架→详情→播放) │
└──────────────────┴──────────────────┘
```

底部导航栏包含两个 Tab：
- **🎵 音乐** — 文件夹浏览 → 音乐播放器 + 歌词同步 + 播放模式切换
- **📚 听书** — 书架 → 书详情（编辑/章节/记忆） → 听书播放 + 字幕 + 书签

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
1. 切换到**音乐** Tab，点击右上角 📂 图标选择音乐文件夹
2. 点击音频文件即可开始播放，自动跳转到播放页
3. 播放页显示歌词（需同目录下存在同名 `.srt` 或 `.ass` 文件）
4. 点击播放控制栏左侧按钮切换播放模式：顺序 → 随机 → 单曲循环

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
