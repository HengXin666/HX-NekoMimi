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

        // 恢复保存的字幕模式
        loadSubtitleMode()

        setupToolbar()
        setupSubtitleList()
        setupControls()
        setupBgmEntry()
        setupSubtitleModeSwitch()
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
     * 双行字幕模式：更新当前行和下一行文本
     */
    private fun updateDualLineMode(index: Int, subtitles: List<com.hx.nekomimi.subtitle.SubtitleEntry>) {
        val dualLineBinding = binding.layoutDualLine

        if (index >= 0 && index < subtitles.size) {
            dualLineBinding.tvCurrentLine.text = subtitles[index].text
            // 下一行
            if (index + 1 < subtitles.size) {
                dualLineBinding.tvNextLine.text = subtitles[index + 1].text
                dualLineBinding.tvNextLine.visibility = View.VISIBLE
            } else {
                dualLineBinding.tvNextLine.text = ""
                dualLineBinding.tvNextLine.visibility = View.INVISIBLE
            }
        } else {
            // 当前没有字幕匹配（间隙期间）
            // 找到下一条即将出现的字幕
            val nextIndex = subtitles.indexOfFirst { it.startMs > (viewModel.currentPosition.value ?: 0L) }
            if (nextIndex >= 0) {
                dualLineBinding.tvCurrentLine.text = ""
                dualLineBinding.tvNextLine.text = subtitles[nextIndex].text
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
