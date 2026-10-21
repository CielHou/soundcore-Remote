package cn.utae.audcore.remote.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import cn.utae.audcore.remote.R
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * App 内二维码扫描视图。识别到 ws:// 或 wss:// 地址后只回调一次，避免重复保存。
 */
class QrScannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val previewView = PreviewView(context)
    private val statusView = TextView(context)
    @Volatile
    private var analysisExecutor: ExecutorService? = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )
    @Volatile
    private var cameraProvider: ProcessCameraProvider? = null
    private var onResult: ((String) -> Unit)? = null

    init {
        addView(
            previewView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        statusView.text = context.getString(R.string.scan_title)
        statusView.setTextColor(0xFFFFFFFF.toInt())
        statusView.setBackgroundColor(0x99000000.toInt())
        val statusParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = (32 * resources.displayMetrics.density).toInt() }
        addView(statusView, statusParams)
    }

    fun start(lifecycleOwner: LifecycleOwner, onResult: (String) -> Unit) {
        this.onResult = onResult
        if (analysisExecutor == null || analysisExecutor?.isShutdown != false) {
            analysisExecutor = Executors.newSingleThreadExecutor()
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            statusView.setText(R.string.camera_permission_denied)
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            provider.unbindAll()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val executor = analysisExecutor ?: return@addListener
            analysis.setAnalyzer(
                executor,
                androidx.core.content.ContextCompat.getMainExecutor(context).let { main ->
                    androidx.camera.core.ImageAnalysis.Analyzer { imageProxy ->
                        main.execute {
                            analyze(imageProxy)
                        }
                    }
                },
            )

            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        onResult = null
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                val callback = onResult
                if (value != null && callback != null) {
                    onResult = null
                    post { callback(value) }
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
