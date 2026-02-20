package com.hx.nekomimi.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hx.nekomimi.R
import com.hx.nekomimi.NekoMimiApp
import com.hx.nekomimi.bgm.BgmManager
import com.hx.nekomimi.data.entity.Book
import com.hx.nekomimi.data.repository.BookRepository
import com.hx.nekomimi.databinding.ActivityMainBinding
import com.hx.nekomimi.databinding.DialogAddBookBinding
import com.hx.nekomimi.databinding.DialogBgmSettingsBinding
import com.hx.nekomimi.ui.adapter.BookAdapter
import com.hx.nekomimi.ui.viewmodel.MainViewModel
import com.hx.nekomimi.util.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var bookAdapter: BookAdapter

    // 选择文件夹后的回调
    private var pendingBookName: String? = null
    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // 持久化 URI 权限
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            addBookFromUri(uri)
        }
    }

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
                BgmManager.stop()
                BgmManager.start(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化 BGM 管理器
        BgmManager.init(this)

        setupToolbar()
        setupRecyclerView()
        setupFab()
        observeData()
    }

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_bgm_settings -> {
                    showBgmSettingsDialog()
                    true
                }
                R.id.action_refresh -> {
                    refreshAllBooks()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        bookAdapter = BookAdapter(
            onClick = { book -> openBookDetail(book) },
            onLongClick = { book -> showDeleteDialog(book) }
        )

        binding.recyclerBooks.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = bookAdapter
        }
    }

    private fun setupFab() {
        binding.fabAddBook.setOnClickListener {
            showAddBookDialog()
        }
    }

    private fun observeData() {
        viewModel.bookItems.observe(this) { items ->
            bookAdapter.submitList(items)
            binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerBooks.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun openBookDetail(book: Book) {
        val intent = Intent(this, BookDetailActivity::class.java).apply {
            putExtra(BookDetailActivity.EXTRA_BOOK_ID, book.id)
        }
        startActivity(intent)
    }

    private fun showDeleteDialog(book: Book) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_book_title)
            .setMessage(getString(R.string.delete_book_message, book.name))
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewModel.deleteBook(book.id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddBookDialog() {
        val dialogBinding = DialogAddBookBinding.inflate(layoutInflater)

        AlertDialog.Builder(this)
            .setTitle(R.string.select_book_folder)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = dialogBinding.editBookName.text?.toString()?.trim()
                if (name.isNullOrEmpty()) {
                    Toast.makeText(this, R.string.input_book_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pendingBookName = name
                folderPicker.launch(null)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addBookFromUri(treeUri: Uri) {
        val bookName = pendingBookName ?: return
        pendingBookName = null

        lifecycleScope.launch {
            try {
                val repository = BookRepository((application as NekoMimiApp).database)

                // 插入书籍记录
                val book = Book(
                    name = bookName,
                    rootUri = treeUri.toString()
                )
                val bookId = repository.insertBook(book)

                // 扫描章节
                val chapters = withContext(Dispatchers.IO) {
                    FileScanner.scanFromUri(this@MainActivity, treeUri, bookId)
                }
                repository.insertChapters(chapters)

                Toast.makeText(
                    this@MainActivity,
                    "添加成功，共 ${chapters.size} 个章节",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@MainActivity,
                    "添加失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun refreshAllBooks() {
        lifecycleScope.launch {
            val repository = BookRepository((application as NekoMimiApp).database)
            val books = withContext(Dispatchers.IO) {
                repository.getAllBooks().value ?: emptyList()
            }

            for (book in books) {
                val treeUri = book.rootUri?.let { Uri.parse(it) } ?: continue
                try {
                    val chapters = withContext(Dispatchers.IO) {
                        FileScanner.scanFromUri(this@MainActivity, treeUri, book.id)
                    }
                    repository.deleteChaptersByBookId(book.id)
                    repository.insertChapters(chapters)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            Toast.makeText(this@MainActivity, "刷新完成", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== 背景音乐 (BGM) 设置 ==========

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
            }
            .show()
    }
}
