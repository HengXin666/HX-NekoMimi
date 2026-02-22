package com.hx.nekomimi.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hx.nekomimi.R
import com.hx.nekomimi.databinding.ItemSubtitleBinding
import com.hx.nekomimi.databinding.ItemSubtitleChatLeftBinding
import com.hx.nekomimi.databinding.ItemSubtitleChatRightBinding
import com.hx.nekomimi.subtitle.SubtitleDisplayMode
import com.hx.nekomimi.subtitle.SubtitleEntry

/**
 * 字幕适配器 — 支持歌词模式、双行模式、对话模式
 *
 * 歌词模式和对话模式使用此 Adapter，双行模式由 PlayerActivity 直接操作双行布局。
 */
class SubtitleAdapter(
    private var displayMode: SubtitleDisplayMode = SubtitleDisplayMode.LYRIC
) : ListAdapter<SubtitleEntry, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        /** ViewType 常量 */
        private const val TYPE_LYRIC = 0
        private const val TYPE_CHAT_LEFT = 1
        private const val TYPE_CHAT_RIGHT = 2

        /** 解析字幕文本中的说话人正则：[说话人] 内容 */
        private val SPEAKER_PATTERN = Regex("""^\[(.+?)]\s*(.*)""", RegexOption.DOT_MATCHES_ALL)

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SubtitleEntry>() {
            override fun areItemsTheSame(oldItem: SubtitleEntry, newItem: SubtitleEntry): Boolean {
                return oldItem.startMs == newItem.startMs && oldItem.endMs == newItem.endMs
            }

            override fun areContentsTheSame(oldItem: SubtitleEntry, newItem: SubtitleEntry): Boolean {
                return oldItem == newItem
            }
        }
    }

    private var highlightIndex: Int = -1

    /**
     * 对话模式下，已知说话人列表（按出场顺序），用于交替左右气泡
     * 第一个说话人放左边，第二个放右边，第三个又放左边...
     * 如果没有说话人标记则默认放左边
     */
    private val speakerOrder = mutableListOf<String>()

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

    fun getHighlightIndex(): Int = highlightIndex

    /**
     * 切换显示模式
     */
    fun setDisplayMode(mode: SubtitleDisplayMode) {
        if (displayMode != mode) {
            displayMode = mode
            notifyDataSetChanged()
        }
    }

    fun getDisplayMode(): SubtitleDisplayMode = displayMode

    override fun submitList(list: List<SubtitleEntry>?) {
        // 对话模式下，预先解析说话人顺序
        speakerOrder.clear()
        list?.forEach { entry ->
            val match = SPEAKER_PATTERN.find(entry.text)
            if (match != null) {
                val speaker = match.groupValues[1].trim()
                if (speaker.isNotEmpty() && speaker !in speakerOrder) {
                    speakerOrder.add(speaker)
                }
            }
        }
        super.submitList(list)
    }

    override fun getItemViewType(position: Int): Int {
        return when (displayMode) {
            SubtitleDisplayMode.LYRIC, SubtitleDisplayMode.DUAL_LINE -> TYPE_LYRIC
            SubtitleDisplayMode.CHAT -> {
                val entry = getItem(position)
                val match = SPEAKER_PATTERN.find(entry.text)
                if (match != null) {
                    val speaker = match.groupValues[1].trim()
                    val speakerIndex = speakerOrder.indexOf(speaker)
                    // 偶数索引（0,2,4...）放左边，奇数索引（1,3,5...）放右边
                    if (speakerIndex >= 0 && speakerIndex % 2 == 1) TYPE_CHAT_RIGHT else TYPE_CHAT_LEFT
                } else {
                    TYPE_CHAT_LEFT
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_CHAT_LEFT -> {
                val binding = ItemSubtitleChatLeftBinding.inflate(inflater, parent, false)
                ChatLeftViewHolder(binding)
            }
            TYPE_CHAT_RIGHT -> {
                val binding = ItemSubtitleChatRightBinding.inflate(inflater, parent, false)
                ChatRightViewHolder(binding)
            }
            else -> {
                val binding = ItemSubtitleBinding.inflate(inflater, parent, false)
                LyricViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val entry = getItem(position)
        val isHighlight = position == highlightIndex

        when (holder) {
            is LyricViewHolder -> holder.bind(entry, isHighlight)
            is ChatLeftViewHolder -> holder.bind(entry, isHighlight)
            is ChatRightViewHolder -> holder.bind(entry, isHighlight)
        }
    }

    // ========== 歌词模式 ViewHolder ==========

    inner class LyricViewHolder(
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

    // ========== 对话模式左侧 ViewHolder ==========

    inner class ChatLeftViewHolder(
        private val binding: ItemSubtitleChatLeftBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: SubtitleEntry, isHighlight: Boolean) {
            val context = binding.root.context
            val match = SPEAKER_PATTERN.find(entry.text)

            if (match != null) {
                val speaker = match.groupValues[1].trim()
                val content = match.groupValues[2].trim()
                binding.tvSpeaker.text = speaker
                binding.tvContent.text = content.ifEmpty { entry.text }
                // 头像显示说话人名字首字
                binding.tvAvatar.text = speaker.firstOrNull()?.toString() ?: "?"
            } else {
                binding.tvSpeaker.text = ""
                binding.tvContent.text = entry.text
                binding.tvAvatar.text = "?"
            }

            // 高亮当前正在说的字幕
            val alpha = if (isHighlight) 1.0f else 0.7f
            binding.root.alpha = alpha

            if (isHighlight) {
                binding.tvContent.setTextColor(ContextCompat.getColor(context, R.color.player_text))
            } else {
                binding.tvContent.setTextColor(ContextCompat.getColor(context, R.color.player_text_secondary))
            }
        }
    }

    // ========== 对话模式右侧 ViewHolder ==========

    inner class ChatRightViewHolder(
        private val binding: ItemSubtitleChatRightBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: SubtitleEntry, isHighlight: Boolean) {
            val context = binding.root.context
            val match = SPEAKER_PATTERN.find(entry.text)

            if (match != null) {
                val speaker = match.groupValues[1].trim()
                val content = match.groupValues[2].trim()
                binding.tvSpeaker.text = speaker
                binding.tvContent.text = content.ifEmpty { entry.text }
                binding.tvAvatar.text = speaker.firstOrNull()?.toString() ?: "?"
            } else {
                binding.tvSpeaker.text = ""
                binding.tvContent.text = entry.text
                binding.tvAvatar.text = "?"
            }

            val alpha = if (isHighlight) 1.0f else 0.7f
            binding.root.alpha = alpha

            if (isHighlight) {
                binding.tvContent.setTextColor(ContextCompat.getColor(context, R.color.on_primary))
            } else {
                binding.tvContent.setTextColor(ContextCompat.getColor(context, R.color.on_primary))
                binding.root.alpha = 0.7f
            }
        }
    }
}
