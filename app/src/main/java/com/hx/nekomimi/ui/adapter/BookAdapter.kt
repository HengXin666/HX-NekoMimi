package com.hx.nekomimi.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hx.nekomimi.R
import com.hx.nekomimi.data.entity.Book
import com.hx.nekomimi.databinding.ItemBookBinding

class BookAdapter(
    private val onClick: (Book) -> Unit,
    private val onLongClick: (Book) -> Unit
) : ListAdapter<BookAdapter.BookItem, BookAdapter.ViewHolder>(DIFF_CALLBACK) {

    data class BookItem(
        val book: Book,
        val chapterCount: Int = 0
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBookBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemBookBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: BookItem) {
            val book = item.book

            binding.tvBookName.text = book.name
            binding.tvChapterCount.text = if (item.chapterCount > 0) {
                "${item.chapterCount} 个章节"
            } else {
                "暂无章节"
            }

            // 封面：如果有封面路径则加载，否则显示默认封面
            if (book.coverPath != null) {
                binding.imgCover.visibility = View.VISIBLE
                binding.defaultCover.visibility = View.GONE
                // 使用 Glide 加载封面
                com.bumptech.glide.Glide.with(binding.root.context)
                    .load(book.coverPath)
                    .centerCrop()
                    .into(binding.imgCover)
            } else {
                binding.imgCover.visibility = View.GONE
                binding.defaultCover.visibility = View.VISIBLE
            }

            binding.root.setOnClickListener { onClick(book) }
            binding.root.setOnLongClickListener {
                onLongClick(book)
                true
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<BookItem>() {
            override fun areItemsTheSame(oldItem: BookItem, newItem: BookItem): Boolean {
                return oldItem.book.id == newItem.book.id
            }

            override fun areContentsTheSame(oldItem: BookItem, newItem: BookItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
