package cn.utae.audcore.remote.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import cn.utae.audcore.remote.App
import cn.utae.audcore.remote.R
import cn.utae.audcore.remote.databinding.FragmentSettingsBinding
import cn.utae.audcore.remote.prefs.AppPreferences
import cn.utae.audcore.remote.scan.QrScannerView
import cn.utae.audcore.remote.service.ConnectionService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/**
 * 设置页：只保留真正需要改的项。
 * 连接地址 / 显示运行日志 / 清除凭证。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val app get() = requireActivity().application as App
    private val viewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory(app.client, app.preferences)
    }
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showQrScanner() else snack(R.string.camera_permission_denied)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = app.preferences

        binding.editUrl.setText(prefs.serverUrl)
        binding.switchShowLogs.isChecked = prefs.showLogs

        binding.buttonCopyEndpoint.setOnClickListener {
            val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
            val endpoint = binding.editUrl.text?.toString().orEmpty().trim()
                .ifBlank { prefs.serverUrl }
            clipboard.setPrimaryClip(ClipData.newPlainText("声核连接地址", endpoint))
            snack(R.string.settings_endpoint_copied)
        }

        binding.switchShowLogs.setOnCheckedChangeListener { _, checked ->
            prefs.showLogs = checked
            (requireActivity() as? MainActivity)?.onSettingsChanged()
        }

        binding.buttonScanQr.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                showQrScanner()
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }

        binding.buttonSave.setOnClickListener {
            val url = binding.editUrl.text?.toString().orEmpty().trim()
            if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
                binding.layoutUrl.error = getString(R.string.error_invalid_url)
                return@setOnClickListener
            }
            binding.layoutUrl.error = null
            val changed = url != prefs.serverUrl
            prefs.serverUrl = url
            if (changed) {
                // 地址变更后立即用新地址重连。
                ConnectionService.stop(requireContext())
                app.client.disconnect()
                ConnectionService.start(requireContext())
                viewModel.connect()
            }
            snack(R.string.settings_saved)
        }

        binding.buttonForget.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.forget_title)
                .setMessage(R.string.forget_message)
                .setPositiveButton(R.string.action_forget) { _, _ ->
                    ConnectionService.stop(requireContext())
                    app.client.forgetAuthorization()
                    snack(R.string.forget_done)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showQrScanner() {
        val scanner = QrScannerView(requireContext())
            .apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.density * 360).toInt()) }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.scan_title)
            .setView(scanner)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        scanner.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                scanner.stop()
            }
        })
        dialog.setOnDismissListener { scanner.stop() }
        dialog.show()
        scanner.start(viewLifecycleOwner) { value ->
            val endpoint = value.trim()
            if (!endpoint.startsWith("ws://") && !endpoint.startsWith("wss://")) {
                snack(R.string.scan_qr_invalid)
                return@start
            }
            binding.editUrl.setText(endpoint)
            app.preferences.serverUrl = endpoint
            ConnectionService.stop(requireContext())
            app.client.disconnect()
            ConnectionService.start(requireContext())
            viewModel.connect()
            // 通知对话框关闭监听器释放扫描器，避免相机资源停留在后台。
            dialog.dismiss()
            snack(R.string.scan_qr_saved)
        }
    }

    private fun snack(messageRes: Int) {
        val root = _binding?.root ?: return
        Snackbar.make(root, messageRes, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
