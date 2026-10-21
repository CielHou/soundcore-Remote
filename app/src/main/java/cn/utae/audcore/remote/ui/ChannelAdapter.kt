package cn.utae.audcore.remote.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.utae.audcore.remote.R
import cn.utae.audcore.remote.data.Channel
import cn.utae.audcore.remote.databinding.ItemChannelBinding

/**
 * 通道列表适配器。
 * - 整行点击：开关该通道静音（编辑模式下为隐藏/显示）
 * - 长按：设为 A 轨 / B 轨
 * - 开关：单独静音/取消静音
 */
class ChannelAdapter(
    private val onRowClick: (Channel) -> Unit,
    private val onToggleMute: (Channel) -> Unit,
    private val onLongPress: (Channel) -> Unit,
) : ListAdapter<Channel, ChannelAdapter.Holder>(Diff) {

    private var busyChannelId: String? = null
    private var editMode = false
    private var hiddenIds: Set<String> = emptySet()
    private var abA: String? = null
    private var abB: String? = null

    fun setBusy(channelId: String?) {
        if (busyChannelId != channelId) {
            busyChannelId = channelId
            for (position in 0 until itemCount) notifyItemChanged(position)
        }
    }

    fun setEditMode(enabled: Boolean, hidden: Set<String>) {
        editMode = enabled
        hiddenIds = hidden
        for (position in 0 until itemCount) notifyItemChanged(position)
    }

    fun setAB(a: String?, b: String?) {
        if (abA == a && abB == b) return
        abA = a
        abB = b
        for (position in 0 until itemCount) notifyItemChanged(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding =
            ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("SetTextI18n")
        fun bind(channel: Channel) {
            val context = binding.root.context
            val order = channel.displayOrder + 1
            binding.textIndex.text = order.toString()
            binding.textName.text = channel.name ?: channel.id
            binding.textGain.text = context.getString(
                R.string.gain_format,
                channel.gain_db ?: 0f,
            )

            val muted = channel.mute == true
            // 开关选中表示通道未静音（与官网演示一致）。
            binding.switchActive.setOnCheckedChangeListener(null)
            binding.switchActive.isChecked = !muted
            binding.switchActive.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked == muted) onToggleMute(channel)
            }

            val isA = channel.id == abA
            val isB = channel.id == abB
            binding.textBadge.visibility = if (isA || isB) View.VISIBLE else View.GONE
            binding.textBadge.text = when {
                isA && isB -> "A/B"
                isA -> context.getString(R.string.ab_badge_a)
                isB -> context.getString(R.string.ab_badge_b)
                else -> ""
            }

            val busy = busyChannelId == channel.id
            val hidden = channel.id in hiddenIds
            binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
            binding.root.alpha = if (busy) 0.55f else 1f
            binding.card.strokeColor = if (muted) {
                context.getColor(R.color.channel_muted_stroke)
            } else {
                context.getColor(R.color.channel_active_stroke)
            }
            // 编辑模式：右侧显示复选框（勾=显示、不勾=隐藏），静音开关让位。
            binding.checkVisible.visibility = if (editMode) View.VISIBLE else View.GONE
            binding.switchActive.visibility = if (editMode) View.GONE else View.VISIBLE
            binding.checkVisible.setOnCheckedChangeListener(null)
            binding.checkVisible.isChecked = !hidden

            binding.textState.setText(
                when {
                    editMode && hidden -> R.string.channel_hidden
                    isA || isB -> if (muted) R.string.channel_muted else R.string.channel_active
                    muted -> R.string.channel_muted
                    else -> R.string.channel_active
                },
            )
            binding.textState.setTextColor(
                if (muted) context.getColor(R.color.muted_text) else context.getColor(R.color.primary),
            )

            binding.switchActive.isEnabled = true
            binding.root.setOnClickListener { onRowClick(channel) }
            binding.root.setOnLongClickListener {
                onLongPress(channel)
                true
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean =
            oldItem == newItem
    }
}
