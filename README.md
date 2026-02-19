
# 🐱 HX-NekoMimi

**NekoMimi** (猫耳) 是一款 Android 本地有声书播放器，专注于**听书**场景，支持字幕显示、播放位置自动记忆等功能。

> 纯本地、无广告、无联网，你的有声书，你做主。
> 🌸 粉色猫耳主题，温柔可爱~

---

## ✨ 功能特性

### 📖 有声书收听
- **书架管理**：导入文件夹作为一本书，显示书籍名称卡片
- **书详情页**：显示章节列表（保留文件夹树结构），支持刷新
- **字幕显示**：支持 SRT 和 ASS/SSA 字幕同步显示
- **ASS 原生渲染**：通过 libass JNI 渲染 ASS 特效字幕
- **播放控制**：播放/暂停、快进10s、快退10s、上一章、下一章
- **进度条**：可拖动进度条，显示当前时间和总时长
- **播放位置自动记忆**：每 3 秒保存一次，双保险存储（Room + SharedPreferences）
- **5 分钟定时记忆**：每播放 5 分钟自动保存一次，UI 显示 "正在保存位置..." → "✓ 已保存"
- **继续收听**：打开书详情页即可看到上次记忆的播放文件和位置，一键继续
- **手动保存**：播放页可手动保存当前位置

### 📂 文件管理
- 通过系统文件选择器 (SAF) 选取有声书文件夹
- 支持删除书籍（不删除实际文件）
- 音频格式：`MP3` `WAV` `M4A` `OGG` `FLAC` `AAC` `WMA` `OPUS` `APE` `ALAC` `M4S`
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
| **本地存储** | SharedPreferences |
| **字幕渲染** | libass (JNI) |
| **最低支持** | Android 8.0 (API 26) |
| **目标版本** | Android 15 (API 35) |

### 项目结构

```
com.hx.nekomimi/
├── MainActivity.kt              # 应用入口，权限请求
├── NekoMimiApp.kt               # Application，Hilt 入口
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt       # Room 数据库定义 (v7)
│   │   ├── dao/
│   │   │   ├── BookDao.kt       # 有声书数据访问
│   │   │   └── PlaybackMemoryDao.kt  # 播放记忆数据访问
│   │   └── entity/
│   │       ├── Book.kt          # 有声书实体 (文件夹=一本书)
│   │       └── PlaybackMemory.kt # 播放记忆实体
│   └── repository/
│       └── PlaybackRepository.kt # 数据仓库 (Room + SP 双写)
├── di/
│   └── AppModule.kt             # Hilt 依赖提供模块
├── player/
│   ├── PlayerManager.kt         # 播放器核心管理器 (纯听书)
│   ├── PlaybackService.kt       # 前台 MediaSessionService
│   └── NekoNotificationProvider.kt # 自定义通知提供者
├── subtitle/
│   ├── SubtitleManager.kt       # 字幕管理器 (自动查找加载)
│   ├── AssRenderer.kt           # ASS/SSA 字幕原生渲染器 (JNI)
│   ├── SrtParser.kt             # SRT 字幕解析器
│   └── model/
│       └── SubtitleCue.kt       # 字幕行模型
└── ui/
    ├── theme/
    │   └── Theme.kt             # 🌸 粉色猫耳主题
    ├── navigation/
    │   ├── Screen.kt            # 路由定义 (3 个页面)
    │   └── NavGraph.kt          # 导航图
    ├── shelf/
    │   └── BookShelfScreen.kt   # 📚 书架主界面 (书籍卡片列表)
    └── player/
        ├── BookDetailScreen.kt  # 📖 书详情页 (记忆位置+章节树)
        └── BookPlayerScreen.kt  # 🎧 播放页 (字幕+控制+记忆)
```

### 页面导航

```
书架 (主界面)
  ├── 添加书籍 (SAF 文件夹选择)
  ├── 删除书籍
  └── 点击书籍 → 书详情页
                    ├── 继续收听 (上次记忆位置)
                    ├── 刷新章节列表
                    └── 点击章节 → 播放页
                                    ├── 字幕显示 (SRT/ASS)
                                    ├── 进度条拖动
                                    ├── 播放/暂停/快进/快退
                                    ├── 上一章/下一章
                                    └── 自动记忆 (3s/5min)
```

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

1. 打开应用进入**书架**主界面
2. 点击右上角 **＋** 按钮，选择有声书文件夹导入
3. 点击书籍卡片进入**书详情页**
4. 查看章节列表（保留文件夹树结构），点击章节开始播放
5. **播放页**自动每 5 分钟记忆位置，显示 "正在保存位置..." → "✓ 已保存"
6. 手动点击 💾 按钮可随时保存位置
7. 下次打开同一本书时，详情页显示上次记忆位置，**一键继续收听**
8. 支持 SRT / ASS 字幕：在音频文件同目录放置同名 `.srt` 或 `.ass` 文件

---

## 📄 License

本项目仅供学习交流使用。
