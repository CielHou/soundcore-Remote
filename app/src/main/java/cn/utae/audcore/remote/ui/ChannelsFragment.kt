package cn.utae.audcore.remote.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import cn.utae.audcore.remote.App
import cn.utae.audcore.remote.R
import cn.utae.audcore.remote.data.Channel
import cn.utae.audcore.remote.data.ConnectionState
import cn.utae.audcore.remote.databinding.FragmentChannelsBinding
import cn.utae.audcore.remote.log.RuntimeLog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 通道页：
 * - 点击通道行 = 开关该通道的静音（不使用独奏）
 * - 长按通道 = 指定 A 轨 / B 轨 / 清除
 * - 底部 A、B 按钮 = 一键互切（一轨开、另一轨关，其余通道不动）
 * 编辑模式下点行改为隐藏/显示通道。
 */
class ChannelsFragment : Fragment() {

    private var _binding: FragmentChannelsBinding? = null
    private val binding get() = _binding!!
    private val app get() = requireActivity().application as App
    private val viewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory(app.client, app.preferences)
    }

    private var editMode = false
    private var hiddenChannelIds = emptySet<String>()

    private val adapter = ChannelAdapter(
        onRowClick = { channel ->
            if (editMode) toggleChannelVisibility(channel) else viewModel.toggleMute(channel)
        },
        onToggleMute = { channel -> viewModel.toggleMute(channel) },
        onLongPress = { channel -> showAssignABDialog(channel) },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentChannelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hiddenChannelIds = parseHiddenIds(app.preferences.hiddenChannelIds)

        binding.recyclerChannels.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerChannels.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener {
            if (viewModel.state.value == ConnectionState.READY) {
                viewModel.refreshChannels()
            } else {
                viewModel.connect()
            }
        }
        binding.buttonA.setOnClickListener { switchAB(targetA = true) }
        binding.buttonB.setOnClickListener { switchAB(targetA = false) }

        observeChannels()
        applyAdapterMode()
        renderABButtons()
        submitChannels(viewModel.channels.value)
    }

    fun isEditMode(): Boolean = editMode

    fun toggleEditMode() {
        editMode = !editMode
        applyAdapterMode()
        // 进入编辑：列出全部通道；点「完成」退出：按勾选结果过滤显示。
        submitChannels(viewModel.channels.value)
        (requireActivity() as? MainActivity)?.refreshToolbarMenuState()
    }

    private fun observeChannels() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.channels,
                    viewModel.busyChannelId,
                    viewModel.state,
                ) { channels, busyId, state ->
                    Triple(channels, busyId, state)
                }.collect { (channels, busyId, state) ->
                    RuntimeLog.i(
                        "通道页收到数据：count=${channels.size}，state=$state，hidden=${hiddenChannelIds.size}",
                    )
                    adapter.setBusy(busyId)
                    adapter.setAB(viewModel.abChannelA, viewModel.abChannelB)
                    submitChannels(channels)
                    renderABButtons()
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun submitChannels(channels: List<Channel>) {
        // 编辑模式展示全部通道（含已隐藏的），方便勾选取消隐藏。
        val visible = if (editMode) channels else channels.filterNot { it.id in hiddenChannelIds }
        adapter.submitList(visible)
        when {
            channels.isEmpty() -> showEmptyHint(R.string.empty_channels)
            !editMode && visible.isEmpty() -> showEmptyHint(R.string.empty_all_hidden)
            else -> binding.emptyHint.visibility = View.GONE
        }
    }

    private fun showEmptyHint(text: Int) {
        binding.emptyHint.setText(text)
        binding.emptyHint.visibility = View.VISIBLE
    }

    private fun toggleChannelVisibility(channel: Channel) {
        hiddenChannelIds =
            if (channel.id in hiddenChannelIds) hiddenChannelIds - channel.id
            else hiddenChannelIds + channel.id
        app.preferences.hiddenChannelIds = hiddenChannelIds.joinToString(",")
        applyAdapterMode()
        submitChannels(viewModel.channels.value)
    }

    private fun applyAdapterMode() {
        adapter.setEditMode(editMode, hiddenChannelIds)
        adapter.setAB(viewModel.abChannelA, viewModel.abChannelB)
    }

    // ---- A/B 双轨 ----

    private fun channelById(id: String?): Channel? =
        id?.let { target -> viewModel.channels.value.firstOrNull { it.id == target } }

    private fun renderABButtons() {
        val binding = _binding ?: return
        val a = channelById(viewModel.abChannelA)
        val b = channelById(viewModel.abChannelB)
        binding.buttonA.text = if (a != null) {
            getString(R.string.ab_slot_a_format, a.name ?: a.id)
        } else {
            getString(R.string.ab_slot_a_empty)
        }
        binding.buttonB.text = if (b != null) {
            getString(R.string.ab_slot_b_format, b.name ?: b.id)
        } else {
            getString(R.string.ab_slot_b_empty)
        }
    }

    private fun switchAB(targetA: Boolean) {
        val a = channelById(viewModel.abChannelA)
        val b = channelById(viewModel.abChannelB)
        // 还没指定通道时直接弹出选择列表，不弹 Snackbar 也不占用版面写说明文字。
        if (a == null) {
            promptAssignSlot(slotA = true)
            return
        }
        if (b == null) {
            promptAssignSlot(slotA = false)
            return
        }
        viewModel.switchAB(a, b, targetA)
    }

    private fun promptAssignSlot(slotA: Boolean) {
        val channels = viewModel.channels.value.filterNot { it.id in hiddenChannelIds }
        if (channels.isEmpty()) return
        val labels = channels.map { it.name ?: it.id }.toTypedArray()
        val currentId = if (slotA) viewModel.abChannelA else viewModel.abChannelB
        val checked = channels.indexOfFirst { it.id == currentId }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (slotA) R.string.ab_pick_title_a else R.string.ab_pick_title_b)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                assignAB(channels[which], slotA)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAssignABDialog(channel: Channel) {
        val items = arrayOf(
            getString(R.string.ab_assign_a),
            getString(R.string.ab_assign_b),
            getString(R.string.ab_clear),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(channel.name ?: channel.id)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> assignAB(channel, slotA = true)
                    1 -> assignAB(channel, slotA = false)
                    else -> {
                        viewModel.clearAB()
                        syncABState()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun assignAB(channel: Channel, slotA: Boolean) {
        // 同一通道不能同时占 A、B 两轨，指派时自动让出另一轨。
        if (slotA && viewModel.abChannelB == channel.id) viewModel.setABChannel(false, "")
        if (!slotA && viewModel.abChannelA == channel.id) viewModel.setABChannel(true, "")
        viewModel.setABChannel(slotA, channel.id)
        syncABState()
    }

    private fun syncABState() {
        adapter.setAB(viewModel.abChannelA, viewModel.abChannelB)
        renderABButtons()
    }

    private fun parseHiddenIds(raw: String): Set<String> =
        raw.split(',')
            .mapNotNull { part -> part.trim().takeIf { it.isNotEmpty() } }
            .toSet()

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerChannels.adapter = null
        _binding = null
    }
}
