package com.hx.nekomimi.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hx.nekomimi.R
import com.hx.nekomimi.data.entity.Chapter
import com.hx.nekomimi.databinding.ItemChapterBinding
import com.hx.nekomimi.util.TimeUtils

class ChapterAdapter(
    private val onClick: (Chapter) -> Unit
) : ListAdapter<Chapter, ChapterAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var currentPlayingId: Long = -1

    fun setCurrentPlaying(chapterId: Long) {
        val oldId = currentPlayingId
        currentPlayingId = chapterId
        // 刷新旧的和新的
        currentList.forEachIndexed { index, chapter ->
            if (chapter.id == oldId || chapter.id == chapterId) {
                notifyItemChanged(index)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChapterBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class ViewHolder(
        private val binding: ItemChapterBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(chapter: Chapter, position: Int) {
            val isPlaying = chapter.id == currentPlayingId
            val context = binding.root.context

            // 序号或播放指示
            if (isPlaying) {
                binding.tvIndex.text = "▶"
                binding.tvIndex.setTextColor(ContextCompat.getColor(context, R.color.primary))
                binding.tvChapterTitle.setTextColor(ContextCompat.getColor(context, R.color.primary))
            } else {
                binding.tvIndex.text = "${position + 1}"
                binding.tvIndex.setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
                binding.tvChapterTitle.setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            }

            binding.tvChapterTitle.text = chapter.title

            // 文件夹路径
            if (chapter.parentFolder.isNotEmpty()) {
                binding.tvFolderPath.visibility = View.VISIBLE
                binding.tvFolderPath.text = chapter.parentFolder
            } else {
                binding.tvFolderPath.visibility = View.GONE
            }

            // 时长
            if (chapter.durationMs > 0) {
                binding.tvDuration.visibility = View.VISIBLE
                binding.tvDuration.text = TimeUtils.formatTime(chapter.durationMs)
            } else {
                binding.tvDuration.visibility = View.GONE
            }

            binding.root.setOnClickListener { onClick(chapter) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Chapter>() {
            override fun areItemsTheSame(oldItem: Chapter, newItem: Chapter): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: Chapter, newItem: Chapter): Boolean {
                return oldItem == newItem
            }
        }
    }
}
