package com.hx.nekomimi.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hx.nekomimi.R
import com.hx.nekomimi.data.entity.Chapter
import com.hx.nekomimi.databinding.ActivityBookDetailBinding
import com.hx.nekomimi.ui.adapter.ChapterAdapter
import com.hx.nekomimi.ui.viewmodel.BookDetailViewModel
import com.hx.nekomimi.util.TimeUtils
import kotlinx.coroutines.launch

class BookDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
    }

    private lateinit var binding: ActivityBookDetailBinding
    private val viewModel: BookDetailViewModel by viewModels()
    private lateinit var chapterAdapter: ChapterAdapter

    private var bookId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookId = intent.getLongExtra(EXTRA_BOOK_ID, 0L)
        if (bookId == 0L) {
            finish()
            return
        }

        viewModel.setBookId(bookId)

        setupToolbar()
        setupRecyclerView()
        observeData()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_refresh_chapters -> {
                    viewModel.refreshChapters()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        chapterAdapter = ChapterAdapter { chapter ->
            openPlayer(chapter)
        }

        binding.recyclerChapters.apply {
            layoutManager = LinearLayoutManager(this@BookDetailActivity)
            adapter = chapterAdapter
        }
    }

    private fun observeData() {
        // 书籍信息
        viewModel.book.observe(this) { book ->
            if (book != null) {
                binding.toolbar.title = book.name
            }
        }

        // 章节列表
        viewModel.chapters.observe(this) { chapters ->
            chapterAdapter.submitList(chapters)

            val isEmpty = chapters.isEmpty()
            binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.recyclerChapters.visibility = if (isEmpty) View.GONE else View.VISIBLE
            binding.tvChapterCount.text = if (isEmpty) "" else getString(R.string.chapter_count, chapters.size)
        }

        // 上次播放进度
        viewModel.progress.observe(this) { progress ->
            if (progress != null && progress.positionMs > 0) {
                binding.cardLastPlayed.visibility = View.VISIBLE

                lifecycleScope.launch {
                    val chapterTitle = viewModel.getChapterTitle(progress.chapterId) ?: "未知章节"
                    val timeStr = TimeUtils.formatTime(progress.positionMs)
                    binding.tvLastPlayedInfo.text = getString(
                        R.string.last_played_info, chapterTitle, timeStr
                    )
                }

                binding.btnContinuePlay.setOnClickListener {
                    openPlayer(progress.chapterId)
                }
            } else {
                binding.cardLastPlayed.visibility = View.GONE
            }
        }

        // 扫描状态
        viewModel.isScanning.observe(this) { scanning ->
            if (scanning) {
                Toast.makeText(this, R.string.scanning_chapters, Toast.LENGTH_SHORT).show()
            }
        }

        // 扫描结果
        viewModel.scanResult.observe(this) { result ->
            if (result != null) {
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                viewModel.clearScanResult()
            }
        }
    }

    private fun openPlayer(chapter: Chapter) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_BOOK_ID, bookId)
            putExtra(PlayerActivity.EXTRA_CHAPTER_ID, chapter.id)
        }
        startActivity(intent)
    }

    private fun openPlayer(chapterId: Long) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_BOOK_ID, bookId)
            putExtra(PlayerActivity.EXTRA_CHAPTER_ID, chapterId)
        }
        startActivity(intent)
    }
}
