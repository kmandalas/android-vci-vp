package com.example.eudiwemu

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

private const val CAMERA_PERMISSION = android.Manifest.permission.CAMERA
private const val CAMERA_REQUEST_CODE = 1001

class QrScannerActivity : FragmentActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(CAMERA_PERMISSION),
                CAMERA_REQUEST_CODE
            )
            return
        }

        startCamera()
    }


    @OptIn(ExperimentalGetImage::class)
    fun startCamera() {
        val previewView = androidx.camera.view.PreviewView(this)
        setContentView(previewView)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val scanner = BarcodeScanning.getClient()

            analysis.setAnalyzer(executor) { imageProxy ->
                if (handled) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val image = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                    )

                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            barcodes.firstOrNull { it.valueType == Barcode.TYPE_TEXT }
                                ?.rawValue
                                ?.let { qr ->
                                    handled = true
                                    handleQr(qr)
                                }
                        }
                        .addOnFailureListener {
                            Log.e("QR", "Scan failed", it)
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleQr(content: String) {
        try {
            val uri = content.toUri()

            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            startActivity(intent)
        } catch (e: Exception) {
            Log.e("QR", "Invalid QR content: $content", e)
        } finally {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == CAMERA_REQUEST_CODE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            finish() // user denied → exit cleanly
        }
    }

}
