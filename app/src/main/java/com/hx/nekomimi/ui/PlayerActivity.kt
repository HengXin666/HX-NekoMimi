package com.hx.nekomimi.ui

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.hx.nekomimi.R
import com.hx.nekomimi.bgm.BgmManager
import com.hx.nekomimi.databinding.ActivityPlayerBinding
import com.hx.nekomimi.databinding.DialogBgmSettingsBinding
import com.hx.nekomimi.service.MediaPlaybackService
import com.hx.nekomimi.subtitle.SubtitleDisplayMode
import com.hx.nekomimi.subtitle.SubtitleHelper
import com.hx.nekomimi.ui.adapter.SubtitleAdapter
import com.hx.nekomimi.ui.viewmodel.PlayerViewModel
import com.hx.nekomimi.util.TimeUtils

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
        const val EXTRA_CHAPTER_ID = "extra_chapter_id"
        private const val PROGRESS_UPDATE_INTERVAL = 300L // 进度更新间隔（毫秒）
        private const val PROGRESS_SAVE_INTERVAL = 5000L  // 进度保存间隔（毫秒）
        private const val SEEK_INCREMENT_MS = 30_000L     // 快进/快退 30 秒
        private const val PREF_NAME = "subtitle_prefs"
        private const val KEY_SUBTITLE_MODE = "subtitle_display_mode"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"

        /** 可选倍速列表 */
        private val SPEED_OPTIONS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
    }

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()

    private lateinit var subtitleAdapter: SubtitleAdapter
    private lateinit var subtitleLayoutManager: LinearLayoutManager

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var lastSaveTime = 0L

    /** 当前字幕显示模式 */
    private var currentDisplayMode = SubtitleDisplayMode.DEFAULT

    /** 当前播放倍速 */
    private var currentSpeed = 1.0f

    /**
     * 双行字幕交替状态：
     * 记录上一次高亮的字幕索引，用于判断交替位置
     * true = 当前高亮在上面（tvCurrentLine），false = 当前高亮在下面（tvNextLine）
     */
    private var dualLineHighlightOnTop = true
    private var lastDualLineIndex = -1

    /**
     * 歌词模式：用户是否正在手动滚动（手动滚动时解除居中锁定）
     * 用户停止滚动 3 秒后自动恢复居中
     */
    private var isUserScrolling = false
    private val resumeCenterRunnable = Runnable { isUserScrolling = false }
    private val USER_SCROLL_RESUME_DELAY = 3000L

    // BGM 文件选择器
    private val bgmFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // 持久化URI权限，防止重启后失效
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // 某些 Provider 不支持持久化，忽略
            }
            BgmManager.setBgmUri(this, uri)
            if (!BgmManager.isEnabled()) {
                BgmManager.setEnabled(this, true)
            } else {
                // 已启用，重新开始播放新文件
                BgmManager.stop()
                BgmManager.start(this)
            }
            updateBgmEntryLabel()
        }
    }

    // 进度更新 Runnable
    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, 0L)
        val chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, 0L)
        if (bookId == 0L || chapterId == 0L) {
            finish()
            return
        }

        viewModel.loadChapter(bookId, chapterId)

        // 初始化 BGM 管理器
        BgmManager.init(this)

        // 恢复保存的字幕模式和倍速
        loadSubtitleMode()
        loadPlaybackSpeed()

        setupToolbar()
        setupSubtitleList()
        setupControls()
        setupBgmEntry()
        setupSubtitleModeSwitch()
        setupSpeedControl()
        observeData()
    }

    override fun onStart() {
        super.onStart()
        initMediaController()
    }

    override fun onResume() {
        super.onResume()
        handler.post(progressUpdater)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(progressUpdater)
        // 暂停时保存进度
        saveCurrentProgress()
    }

    override fun onStop() {
        super.onStop()
        releaseMediaController()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        // 如果听书停止，也停止 BGM
        if (mediaController?.isPlaying != true) {
            BgmManager.pause()
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupSubtitleList() {
        subtitleAdapter = SubtitleAdapter(currentDisplayMode)
        subtitleLayoutManager = LinearLayoutManager(this)

        binding.recyclerSubtitles.apply {
            layoutManager = subtitleLayoutManager
            adapter = subtitleAdapter
            itemAnimator = null // 禁用动画避免闪烁

            // 监听用户手动滚动（歌词模式下，手动滚动时解除居中）
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        // 用户开始手动滚动
                        isUserScrolling = true
                        handler.removeCallbacks(resumeCenterRunnable)
                    } else if (newState == RecyclerView.SCROLL_STATE_IDLE && isUserScrolling) {
                        // 用户停止滚动，延迟后恢复居中
                        handler.postDelayed(resumeCenterRunnable, USER_SCROLL_RESUME_DELAY)
                    }
                }
            })
        }
    }

    // ========== 字幕模式切换 ==========

    private fun setupSubtitleModeSwitch() {
        updateSubtitleModeLabel()

        binding.layoutSubtitleMode.setOnClickListener {
            showSubtitleModeDialog()
        }
    }

    private fun showSubtitleModeDialog() {
        val modeNames = arrayOf(
            getString(R.string.subtitle_mode_lyric),
            getString(R.string.subtitle_mode_dual),
            getString(R.string.subtitle_mode_chat)
        )
        val checkedIndex = currentDisplayMode.ordinal

        MaterialAlertDialogBuilder(this, R.style.Theme_NekoMimi_Dialog)
            .setTitle(R.string.subtitle_mode_setting)
            .setSingleChoiceItems(modeNames, checkedIndex) { dialog, which ->
                val newMode = SubtitleDisplayMode.fromOrdinal(which)
                switchDisplayMode(newMode)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun switchDisplayMode(mode: SubtitleDisplayMode) {
        if (currentDisplayMode == mode) return
        currentDisplayMode = mode
        saveSubtitleMode()
        updateSubtitleModeLabel()
        subtitleAdapter.setDisplayMode(mode)
        applyDisplayMode()
    }

    /**
     * 根据当前模式显示/隐藏对应的视图
     */
    private fun applyDisplayMode() {
        val hasSubtitles = (viewModel.subtitles.value?.isNotEmpty() == true)

        if (!hasSubtitles) {
            binding.recyclerSubtitles.visibility = View.GONE
            binding.layoutDualLine.root.visibility = View.GONE
            binding.tvNoSubtitle.visibility = View.VISIBLE
            return
        }

        binding.tvNoSubtitle.visibility = View.GONE

        when (currentDisplayMode) {
            SubtitleDisplayMode.LYRIC -> {
                binding.recyclerSubtitles.visibility = View.VISIBLE
                binding.layoutDualLine.root.visibility = View.GONE
            }
            SubtitleDisplayMode.DUAL_LINE -> {
                binding.recyclerSubtitles.visibility = View.GONE
                binding.layoutDualLine.root.visibility = View.VISIBLE
            }
            SubtitleDisplayMode.CHAT -> {
                binding.recyclerSubtitles.visibility = View.VISIBLE
                binding.layoutDualLine.root.visibility = View.GONE
            }
        }
    }

    private fun updateSubtitleModeLabel() {
        val label = when (currentDisplayMode) {
            SubtitleDisplayMode.LYRIC -> getString(R.string.subtitle_mode_lyric)
            SubtitleDisplayMode.DUAL_LINE -> getString(R.string.subtitle_mode_dual)
            SubtitleDisplayMode.CHAT -> getString(R.string.subtitle_mode_chat)
        }
        binding.tvSubtitleModeLabel.text = label
    }

    private fun saveSubtitleMode() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            .edit()
            .putInt(KEY_SUBTITLE_MODE, currentDisplayMode.ordinal)
            .apply()
    }

    private fun loadSubtitleMode() {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val ordinal = prefs.getInt(KEY_SUBTITLE_MODE, SubtitleDisplayMode.DEFAULT.ordinal)
        currentDisplayMode = SubtitleDisplayMode.fromOrdinal(ordinal)
    }

    // ========== 倍速控制 ==========

    private fun setupSpeedControl() {
        updateSpeedLabel()
        binding.tvSpeedLabel.setOnClickListener {
            showSpeedDialog()
        }
    }

    private fun showSpeedDialog() {
        val speedLabels = SPEED_OPTIONS.map { speed ->
            if (speed == speed.toLong().toFloat()) "${speed.toLong().toInt()}x" else "${speed}x"
        }.toTypedArray()

        val checkedIndex = SPEED_OPTIONS.indexOfFirst { it == currentSpeed }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.Theme_NekoMimi_Dialog)
            .setTitle(R.string.speed_setting)
            .setSingleChoiceItems(speedLabels, checkedIndex) { dialog, which ->
                val newSpeed = SPEED_OPTIONS[which]
                setPlaybackSpeed(newSpeed)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        savePlaybackSpeed()
        updateSpeedLabel()
        mediaController?.setPlaybackSpeed(speed)
    }

    private fun updateSpeedLabel() {
        val label = if (currentSpeed == currentSpeed.toLong().toFloat()) {
            "${currentSpeed.toInt()}.0×"
        } else {
            "${currentSpeed}×"
        }
        binding.tvSpeedLabel.text = label
        // 非1.0倍速时高亮显示
        if (currentSpeed != 1.0f) {
            binding.tvSpeedLabel.setTextColor(getColor(R.color.primary))
        } else {
            binding.tvSpeedLabel.setTextColor(getColor(R.color.player_text_secondary))
        }
    }

    private fun savePlaybackSpeed() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            .edit()
            .putFloat(KEY_PLAYBACK_SPEED, currentSpeed)
            .apply()
    }

    private fun loadPlaybackSpeed() {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        currentSpeed = prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
    }

    private fun setupControls() {
        // 播放/暂停按钮
        binding.btnPlayPause.setOnClickListener {
            mediaController?.let { controller ->
                if (controller.isPlaying) {
                    controller.pause()
                } else {
                    controller.play()
                }
            }
        }

        // 快退 30s
        binding.btnRewind.setOnClickListener {
            mediaController?.let { controller ->
                val newPos = (controller.currentPosition - SEEK_INCREMENT_MS).coerceAtLeast(0)
                controller.seekTo(newPos)
            }
        }

        // 快进 30s
        binding.btnForward.setOnClickListener {
            mediaController?.let { controller ->
                val newPos = (controller.currentPosition + SEEK_INCREMENT_MS)
                    .coerceAtMost(controller.duration.coerceAtLeast(0))
                controller.seekTo(newPos)
            }
        }

        // 进度条拖动
        binding.sliderProgress.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                isUserSeeking = false
                mediaController?.let { controller ->
                    val duration = controller.duration.coerceAtLeast(0)
                    val position = (slider.value / 100f * duration).toLong()
                    controller.seekTo(position)
                }
            }
        })

        binding.sliderProgress.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val duration = mediaController?.duration?.coerceAtLeast(0) ?: 0
                val position = (value / 100f * duration).toLong()
                binding.tvCurrentTime.text = TimeUtils.formatTime(position)
            }
        }
    }

    private fun observeData() {
        // 章节信息
        viewModel.chapter.observe(this) { chapter ->
            if (chapter != null) {
                binding.toolbar.title = chapter.title
                binding.toolbar.subtitle = chapter.parentFolder.ifEmpty { null }
            }
        }

        // 字幕
        viewModel.subtitles.observe(this) { subtitles ->
            if (subtitles.isNotEmpty()) {
                subtitleAdapter.submitList(subtitles)
                applyDisplayMode()
            } else {
                binding.recyclerSubtitles.visibility = View.GONE
                binding.layoutDualLine.root.visibility = View.GONE
                binding.tvNoSubtitle.visibility = View.VISIBLE
            }
        }

        // 上次播放位置提示
        viewModel.lastProgress.observe(this) { progress ->
            if (progress != null && progress.positionMs > 0) {
                binding.cardLastPosition.visibility = View.VISIBLE
                binding.tvLastPositionHint.text = getString(
                    R.string.last_position_hint,
                    TimeUtils.formatTime(progress.positionMs)
                )
                binding.btnJumpToPosition.setOnClickListener {
                    mediaController?.seekTo(progress.positionMs)
                    binding.cardLastPosition.visibility = View.GONE
                    viewModel.clearLastProgress()
                }
            } else {
                binding.cardLastPosition.visibility = View.GONE
            }
        }
    }

    // ========== MediaController 管理 ==========

    private fun initMediaController() {
        val sessionToken = SessionToken(this, ComponentName(this, MediaPlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future

        future.addListener({
            try {
                mediaController = future.get()
                onMediaControllerReady()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun onMediaControllerReady() {
        val controller = mediaController ?: return

        // 设置播放器监听
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                viewModel.updatePlayingState(isPlaying)
                updatePlayPauseButton(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val duration = controller.duration
                    viewModel.updateDuration(duration)
                    binding.tvTotalTime.text = TimeUtils.formatTime(duration)
                    binding.sliderProgress.valueTo = 100f
                }
            }
        })

        // 设置保存的倍速
        controller.setPlaybackSpeed(currentSpeed)

        // 开始播放
        startPlayback(controller)
    }

    private fun startPlayback(controller: MediaController) {
        val audioUri = viewModel.getAudioUri() ?: return

        val mediaItem = MediaItem.fromUri(audioUri)
        controller.setMediaItem(mediaItem)
        controller.prepare()
        controller.play()
    }

    private fun releaseMediaController() {
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        controllerFuture = null
        mediaController = null
    }

    // ========== 进度更新 ==========

    private fun updateProgress() {
        val controller = mediaController ?: return
        if (controller.playbackState == Player.STATE_IDLE) return

        val position = controller.currentPosition
        val duration = controller.duration.coerceAtLeast(1)

        viewModel.updatePosition(position)

        // 更新进度条（用户拖动时不更新）
        if (!isUserSeeking) {
            binding.tvCurrentTime.text = TimeUtils.formatTime(position)
            binding.tvTotalTime.text = TimeUtils.formatTime(duration)

            val progress = (position.toFloat() / duration * 100f).coerceIn(0f, 100f)
            binding.sliderProgress.value = progress
        }

        // 根据模式更新字幕
        updateSubtitleDisplay(position)

        // 定期保存进度
        val now = System.currentTimeMillis()
        if (now - lastSaveTime > PROGRESS_SAVE_INTERVAL) {
            lastSaveTime = now
            viewModel.saveProgress(position)
        }
    }

    /**
     * 根据当前字幕模式更新字幕显示
     */
    private fun updateSubtitleDisplay(positionMs: Long) {
        val subtitles = viewModel.subtitles.value ?: return
        if (subtitles.isEmpty()) return

        val index = SubtitleHelper.getCurrentSubtitleIndex(subtitles, positionMs)

        when (currentDisplayMode) {
            SubtitleDisplayMode.LYRIC -> updateLyricMode(index)
            SubtitleDisplayMode.DUAL_LINE -> updateDualLineMode(index, subtitles)
            SubtitleDisplayMode.CHAT -> updateChatMode(index)
        }
    }

    /**
     * 歌词模式：高亮当前行并垂直居中滚动
     */
    private fun updateLyricMode(index: Int) {
        subtitleAdapter.setHighlightIndex(index)

        if (index >= 0 && !isUserScrolling) {
            // 计算使当前行居中的滚动偏移
            val recyclerView = binding.recyclerSubtitles
            val recyclerHeight = recyclerView.height
            val targetOffset = recyclerHeight / 2

            // 使用 scrollToPositionWithOffset 将目标项置于中间
            subtitleLayoutManager.scrollToPositionWithOffset(index, targetOffset)
        }
    }

    /**
     * 双行字幕模式：交替高亮
     *
     * 逻辑说明：
     * - 两行字幕交替显示，上面说完了高亮下面，同时上面变成下一句
     * - 例如：
     *   初始：上=句1(高亮) 下=句2(暗)
     *   切换：上=句3(暗)   下=句2(高亮)
     *   切换：上=句3(高亮) 下=句4(暗)
     *   如此交替...
     */
    private fun updateDualLineMode(index: Int, subtitles: List<com.hx.nekomimi.subtitle.SubtitleEntry>) {
        val dualLineBinding = binding.layoutDualLine

        if (index >= 0 && index < subtitles.size) {
            // 字幕索引发生变化时，切换高亮位置
            if (index != lastDualLineIndex) {
                if (lastDualLineIndex == -1) {
                    // 首次显示，高亮在上面
                    dualLineHighlightOnTop = true
                } else {
                    // 交替切换
                    dualLineHighlightOnTop = !dualLineHighlightOnTop
                }
                lastDualLineIndex = index
            }

            val currentText = subtitles[index].text
            val nextText = if (index + 1 < subtitles.size) subtitles[index + 1].text else ""

            if (dualLineHighlightOnTop) {
                // 高亮在上面：上=当前句(高亮)，下=下一句(暗)
                dualLineBinding.tvCurrentLine.text = currentText
                dualLineBinding.tvNextLine.text = nextText
                // 上面高亮样式
                dualLineBinding.tvCurrentLine.setTextColor(getColor(R.color.subtitle_highlight))
                dualLineBinding.tvCurrentLine.textSize = 20f
                dualLineBinding.tvCurrentLine.alpha = 1.0f
                dualLineBinding.tvCurrentLine.paint.isFakeBoldText = true
                // 下面暗色样式
                dualLineBinding.tvNextLine.setTextColor(getColor(R.color.player_text_secondary))
                dualLineBinding.tvNextLine.textSize = 16f
                dualLineBinding.tvNextLine.alpha = 0.6f
                dualLineBinding.tvNextLine.paint.isFakeBoldText = false
            } else {
                // 高亮在下面：上=下一句(暗)，下=当前句(高亮)
                dualLineBinding.tvCurrentLine.text = nextText
                dualLineBinding.tvNextLine.text = currentText
                // 上面暗色样式
                dualLineBinding.tvCurrentLine.setTextColor(getColor(R.color.player_text_secondary))
                dualLineBinding.tvCurrentLine.textSize = 16f
                dualLineBinding.tvCurrentLine.alpha = 0.6f
                dualLineBinding.tvCurrentLine.paint.isFakeBoldText = false
                // 下面高亮样式
                dualLineBinding.tvNextLine.setTextColor(getColor(R.color.subtitle_highlight))
                dualLineBinding.tvNextLine.textSize = 20f
                dualLineBinding.tvNextLine.alpha = 1.0f
                dualLineBinding.tvNextLine.paint.isFakeBoldText = true
            }

            dualLineBinding.tvCurrentLine.visibility = View.VISIBLE
            dualLineBinding.tvNextLine.visibility = if (nextText.isNotEmpty() || !dualLineHighlightOnTop) View.VISIBLE else View.INVISIBLE

            // 触发重绘
            dualLineBinding.tvCurrentLine.invalidate()
            dualLineBinding.tvNextLine.invalidate()
        } else {
            // 当前没有字幕匹配（间隙期间）
            // 找到下一条即将出现的字幕
            val nextIndex = subtitles.indexOfFirst { it.startMs > (viewModel.currentPosition.value ?: 0L) }
            if (nextIndex >= 0) {
                if (dualLineHighlightOnTop) {
                    // 上一次高亮在上面，那下一句预览放下面
                    dualLineBinding.tvCurrentLine.text = ""
                    dualLineBinding.tvNextLine.text = subtitles[nextIndex].text
                    dualLineBinding.tvCurrentLine.alpha = 0.6f
                    dualLineBinding.tvNextLine.setTextColor(getColor(R.color.player_text_secondary))
                    dualLineBinding.tvNextLine.textSize = 16f
                    dualLineBinding.tvNextLine.alpha = 0.6f
                    dualLineBinding.tvNextLine.paint.isFakeBoldText = false
                } else {
                    // 上一次高亮在下面，那下一句预览放上面
                    dualLineBinding.tvCurrentLine.text = subtitles[nextIndex].text
                    dualLineBinding.tvNextLine.text = ""
                    dualLineBinding.tvCurrentLine.setTextColor(getColor(R.color.player_text_secondary))
                    dualLineBinding.tvCurrentLine.textSize = 16f
                    dualLineBinding.tvCurrentLine.alpha = 0.6f
                    dualLineBinding.tvCurrentLine.paint.isFakeBoldText = false
                }
                dualLineBinding.tvCurrentLine.visibility = View.VISIBLE
                dualLineBinding.tvNextLine.visibility = View.VISIBLE
            } else {
                dualLineBinding.tvCurrentLine.text = ""
                dualLineBinding.tvNextLine.text = ""
            }
        }
    }

    /**
     * 对话模式：高亮当前行并滚动到可见
     */
    private fun updateChatMode(index: Int) {
        subtitleAdapter.setHighlightIndex(index)

        // 自动滚动到当前字幕
        if (index >= 0) {
            val firstVisible = subtitleLayoutManager.findFirstCompletelyVisibleItemPosition()
            val lastVisible = subtitleLayoutManager.findLastCompletelyVisibleItemPosition()

            if (index !in firstVisible..lastVisible) {
                binding.recyclerSubtitles.smoothScrollToPosition(index)
            }
        }
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        binding.btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        // 听书播放状态变化时，联动背景音乐
        if (isPlaying) {
            if (BgmManager.isEnabled()) {
                if (BgmManager.isPlaying()) {
                    // 已在播放
                } else {
                    BgmManager.start(this)
                }
            }
        } else {
            BgmManager.pause()
        }
    }

    private fun saveCurrentProgress() {
        val position = mediaController?.currentPosition ?: return
        if (position > 0) {
            viewModel.saveProgress(position)
        }
    }

    // ========== 背景音乐 (BGM) ==========

    private fun setupBgmEntry() {
        updateBgmEntryLabel()
        binding.layoutBgmEntry.setOnClickListener {
            showBgmSettingsDialog()
        }
    }

    /**
     * 更新播放器底部 BGM 入口标签状态
     */
    private fun updateBgmEntryLabel() {
        val enabled = BgmManager.isEnabled()
        val hasFile = BgmManager.getBgmUri() != null
        if (enabled && hasFile) {
            binding.tvBgmLabel.text = getString(R.string.bgm_settings) + " · ON"
            binding.ivBgmIcon.setColorFilter(getColor(R.color.primary))
            binding.tvBgmLabel.setTextColor(getColor(R.color.primary))
        } else {
            binding.tvBgmLabel.text = getString(R.string.bgm_settings)
            binding.ivBgmIcon.setColorFilter(getColor(R.color.player_text_secondary))
            binding.tvBgmLabel.setTextColor(getColor(R.color.player_text_secondary))
        }
    }

    /**
     * 显示 BGM 设置弹窗
     */
    private fun showBgmSettingsDialog() {
        val dialogBinding = DialogBgmSettingsBinding.inflate(layoutInflater)

        // 初始化开关状态
        dialogBinding.switchBgmEnable.isChecked = BgmManager.isEnabled()

        // 初始化文件名显示
        val displayName = BgmManager.getBgmDisplayName(this)
        if (displayName != null) {
            dialogBinding.tvBgmFileName.text = displayName
            dialogBinding.btnClearBgm.visibility = View.VISIBLE
        } else {
            dialogBinding.tvBgmFileName.text = getString(R.string.bgm_no_file)
            dialogBinding.btnClearBgm.visibility = View.GONE
        }

        // 初始化音量滑块
        val currentVolume = (BgmManager.getVolume() * 100).toInt()
        dialogBinding.sliderBgmVolume.value = currentVolume.toFloat()
        dialogBinding.tvVolumePercent.text = getString(R.string.bgm_volume_percent, currentVolume)

        // 开关监听
        dialogBinding.switchBgmEnable.setOnCheckedChangeListener { _, isChecked ->
            BgmManager.setEnabled(this, isChecked)
            updateBgmEntryLabel()
        }

        // 选择文件
        dialogBinding.layoutSelectBgm.setOnClickListener {
            bgmFilePicker.launch(arrayOf("audio/*"))
        }

        // 清除文件
        dialogBinding.btnClearBgm.setOnClickListener {
            BgmManager.setBgmUri(this, null)
            dialogBinding.tvBgmFileName.text = getString(R.string.bgm_no_file)
            dialogBinding.btnClearBgm.visibility = View.GONE
            updateBgmEntryLabel()
        }

        // 音量滑块
        dialogBinding.sliderBgmVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val vol = value / 100f
                BgmManager.setVolume(this, vol)
                dialogBinding.tvVolumePercent.text = getString(R.string.bgm_volume_percent, value.toInt())
            }
        }

        MaterialAlertDialogBuilder(this, R.style.Theme_NekoMimi_Dialog)
            .setTitle(R.string.bgm_settings)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.confirm) { dialog, _ ->
                dialog.dismiss()
                updateBgmEntryLabel()
            }
            .show()
    }
}
