
# 🐱 HX-NekoMimi

**NekoMimi** (猫耳) 是一款 Android 本地音频播放器，专注于**音乐播放**与**有声书收听**两大场景，支持歌词/字幕显示、播放位置记忆、书签标记等功能。

> 纯本地、无广告、无联网，你的音频，你做主。

---

## ✨ 功能特性

### 🎵 音乐播放
- 文件夹浏览模式，选择文件夹即可加载全部音频
- 自动按文件名排序生成播放列表
- 上一曲 / 下一曲 / 暂停 / 拖动进度条
- **歌词同步显示**：自动加载同名字幕文件，支持 **SRT** 和 **ASS/SSA** 格式
- ASS 字幕特效渲染：颜色、加粗、斜体、字号、淡入淡出、描边、阴影
- 歌词列表自动滚动到当前行，高亮显示

### 📖 有声书收听
- 播放位置**自动记忆**：每 3 秒保存一次，切换文件/退出应用后自动恢复
- **双保险存储**：Room 数据库 + SharedPreferences 快照，进程被杀也不丢失
- **书签系统**：手动标记位置，支持命名、回溯、删除
- 记忆历史列表：按文件夹分组，显示距当前位置的偏移量
- 手动记忆按钮：一键保存当前位置

### 📂 文件管理
- 通过系统文件选择器 (SAF) 选取音频文件夹
- 支持子文件夹浏览，显示每个子文件夹的音频数量
- 支持返回上级目录
- 支持格式：`MP3` `WAV` `M4A` `OGG` `FLAC` `AAC` `WMA` `OPUS` `APE` `ALAC`

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
├── MainActivity.kt              # 应用入口，权限请求，底部导航
├── NekoMimiApp.kt               # Application，Hilt 入口
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt       # Room 数据库定义
│   │   ├── dao/
│   │   │   ├── BookmarkDao.kt   # 书签数据访问
│   │   │   └── PlaybackMemoryDao.kt  # 播放记忆数据访问
│   │   └── entity/
│   │       ├── Bookmark.kt      # 书签实体
│   │       └── PlaybackMemory.kt # 播放记忆实体
│   └── repository/
│       └── PlaybackRepository.kt # 数据仓库 (Room + SP 双写)
├── di/
│   └── AppModule.kt             # Hilt 依赖提供模块
├── player/
│   ├── PlayerManager.kt         # 播放器核心管理器 (单例)
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
    │   └── Theme.kt             # Material 3 主题
    ├── navigation/
    │   ├── Screen.kt            # 路由定义
    │   └── NavGraph.kt          # 导航图
    ├── home/
    │   └── HomeScreen.kt        # 主页 (文件夹浏览)
    ├── player/
    │   ├── MusicPlayerScreen.kt # 音乐播放页 (歌词同步)
    │   └── BookPlayerScreen.kt  # 听书播放页 (书签+记忆)
    └── bookmark/
        └── BookmarkScreen.kt    # 全部书签管理页
```

### 页面导航

```
┌──────────┬──────────┬──────────┬──────────┐
│   主页   │   音乐   │   听书   │   书签   │
│  (Home)  │ (Music)  │  (Book)  │(Bookmark)│
└──────────┴──────────┴──────────┴──────────┘
```

底部导航栏包含四个页面：
- **主页** — 文件夹浏览与文件选择
- **音乐** — 音乐播放器 + 歌词同步显示
- **听书** — 有声书播放器 + 书签管理 + 记忆历史
- **书签** — 全局书签汇总（按文件分组）

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

1. 首次打开应用后，点击主页右上角的 📂 图标选择一个包含音频文件的文件夹
2. 点击文件列表中的音频文件开始播放
3. 切换到**音乐**页面查看歌词（需要同目录下存在同名 `.srt` 或 `.ass` 字幕文件）
4. 切换到**听书**页面可以添加书签、查看播放记忆历史
5. 播放位置会自动保存，下次打开同一文件时自动恢复

---

## 📄 License

本项目仅供学习交流使用。
