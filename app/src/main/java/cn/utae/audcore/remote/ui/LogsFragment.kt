package cn.utae.audcore.remote.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import cn.utae.audcore.remote.R
import cn.utae.audcore.remote.databinding.FragmentLogsBinding
import cn.utae.audcore.remote.log.RuntimeLog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/** 运行日志页：只在设置里打开「显示运行日志」后出现在抽屉中。 */
class LogsFragment : Fragment() {

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.buttonCopyLog.setOnClickListener { copyLog() }
        binding.buttonClearLog.setOnClickListener { confirmClear() }
        renderLog()
    }

    override fun onResume() {
        super.onResume()
        renderLog()
    }

    private fun renderLog() {
        val binding = _binding ?: return
        binding.textLog.text = RuntimeLog.read(requireContext())
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copyLog() {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
        val text = binding.textLog.text?.toString().orEmpty()
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.log_title), text))
        Snackbar.make(binding.root, R.string.log_copied, Snackbar.LENGTH_SHORT).show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.log_clear)
            .setMessage(R.string.log_clear_message)
            .setPositiveButton(R.string.log_clear) { _, _ ->
                RuntimeLog.clear(requireContext())
                renderLog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
