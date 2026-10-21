package cn.utae.audcore.remote.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cn.utae.audcore.remote.App
import cn.utae.audcore.remote.R
import cn.utae.audcore.remote.data.ConnectionState
import cn.utae.audcore.remote.databinding.FragmentConnectBinding
import cn.utae.audcore.remote.log.RuntimeLog
import cn.utae.audcore.remote.scan.QrScannerView
import cn.utae.audcore.remote.service.ConnectionService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/** 连接页：大标题 + 状态卡 + 主连接按钮。 */
class ConnectFragment : Fragment() {

    private var _binding: FragmentConnectBinding? = null
    private val binding get() = _binding!!
    private val app get() = requireActivity().application as App
    private val viewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory(app.client, app.preferences)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showQrScanner()
        } else {
            snack(R.string.camera_permission_denied)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonConnect.setOnClickListener {
            when (viewModel.state.value) {
                ConnectionState.IDLE, ConnectionState.FAILED -> startConnect()
                else -> confirmDisconnect()
            }
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    RuntimeLog.i("连接页状态：$state")
                    renderState(state)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.lastError.collect { error ->
                    binding.textError.text = error.orEmpty()
                    binding.textError.visibility =
                        if (error.isNullOrBlank()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun renderState(state: ConnectionState) {
        binding.textStatus.setText(
            when (state) {
                ConnectionState.IDLE -> R.string.state_idle
                ConnectionState.CONNECTING -> R.string.state_connecting
                ConnectionState.AUTHORIZATION_PENDING -> R.string.state_auth_pending
                ConnectionState.READY -> R.string.state_ready
                ConnectionState.RECONNECTING -> R.string.state_reconnecting
                ConnectionState.FAILED -> R.string.state_failed
            },
        )
        binding.cardAuthorization.visibility =
            if (state == ConnectionState.AUTHORIZATION_PENDING) View.VISIBLE else View.GONE
        binding.textEndpoint.text =
            getString(R.string.endpoint_format, app.preferences.serverUrl)
        val connected = state == ConnectionState.READY ||
            state == ConnectionState.AUTHORIZATION_PENDING ||
            state == ConnectionState.CONNECTING ||
            state == ConnectionState.RECONNECTING
        binding.buttonConnect.setText(
            if (connected) R.string.action_disconnect else R.string.action_connect,
        )
    }

    private fun startConnect() {
        maybeRequestNotificationPermission()
        ConnectionService.start(requireContext())
        viewModel.connect()
    }

    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.disconnect_title)
            .setMessage(R.string.disconnect_message)
            .setPositiveButton(R.string.action_disconnect) { _, _ ->
                ConnectionService.stop(requireContext())
                viewModel.disconnect()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showQrScanner() {
        val scanner = QrScannerView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 360).toInt(),
            )
        }
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
        scanner.start(viewLifecycleOwner) { rawValue ->
            val endpoint = rawValue.trim()
            if (!endpoint.startsWith("ws://") && !endpoint.startsWith("wss://")) {
                snack(R.string.scan_qr_invalid)
                return@start
            }
            applyScannedEndpoint(endpoint)
            dialog.dismiss()
        }
    }

    private fun applyScannedEndpoint(endpoint: String) {
        app.preferences.serverUrl = endpoint
        ConnectionService.stop(requireContext())
        app.client.disconnect()
        ConnectionService.start(requireContext())
        viewModel.connect()
        snack(R.string.scan_qr_saved)
    }

    private fun snack(messageRes: Int) {
        val root = _binding?.root ?: return
        Snackbar.make(root, messageRes, Snackbar.LENGTH_SHORT).show()
    }

    private fun maybeRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
