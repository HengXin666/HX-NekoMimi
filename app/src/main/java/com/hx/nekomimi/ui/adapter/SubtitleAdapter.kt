package com.hx.nekomimi.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hx.nekomimi.R
import com.hx.nekomimi.databinding.ItemSubtitleBinding
import com.hx.nekomimi.subtitle.SubtitleEntry

class SubtitleAdapter : ListAdapter<SubtitleEntry, SubtitleAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var highlightIndex: Int = -1

    /**
     * 设置当前高亮的字幕索引
     */
    fun setHighlightIndex(index: Int) {
        val oldIndex = highlightIndex
        highlightIndex = index
        if (oldIndex >= 0 && oldIndex < currentList.size) {
            notifyItemChanged(oldIndex)
        }
        if (index >= 0 && index < currentList.size) {
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSubtitleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position == highlightIndex)
    }

    inner class ViewHolder(
        private val binding: ItemSubtitleBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: SubtitleEntry, isHighlight: Boolean) {
            val context = binding.root.context
            binding.tvSubtitleText.text = entry.text

            if (isHighlight) {
                binding.tvSubtitleText.setTextColor(
                    ContextCompat.getColor(context, R.color.subtitle_highlight)
                )
                binding.tvSubtitleText.alpha = 1.0f
                binding.tvSubtitleText.textSize = 18f
            } else {
                binding.tvSubtitleText.setTextColor(
                    ContextCompat.getColor(context, R.color.player_text_secondary)
                )
                binding.tvSubtitleText.alpha = 0.6f
                binding.tvSubtitleText.textSize = 15f
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SubtitleEntry>() {
            override fun areItemsTheSame(oldItem: SubtitleEntry, newItem: SubtitleEntry): Boolean {
                return oldItem.startMs == newItem.startMs && oldItem.endMs == newItem.endMs
            }

            override fun areContentsTheSame(oldItem: SubtitleEntry, newItem: SubtitleEntry): Boolean {
                return oldItem == newItem
            }
        }
    }
}
