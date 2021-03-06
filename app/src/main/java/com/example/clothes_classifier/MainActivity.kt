// Copyright 2020 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.example.clothes_classifier

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.tfe_dc_activity_main.*
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.*

class MainActivity : AppCompatActivity() {

    private var digitClassifier = ClothesClassifier(this)

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.tfe_dc_activity_main)

        // Setup clear drawing button
        clear_button.setOnClickListener {
            predicted_text?.text = getString(R.string.tfe_dc_prediction_text_placeholder)
        }

        shutter_button.setOnClickListener {
            classifyDrawing()
        }

        setupDigitClassifier()

        startBackgroundThread()

        /**
         * ?????????????????????????????????????????????
         */
        preview_view.surfaceTextureListener = surfaceTextureListener
    }

    private lateinit var imageReader: ImageReader

    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var previewRequest: CaptureRequest
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var cameraDevice: CameraDevice? = null
    private lateinit var captureSession: CameraCaptureSession

    /**
     * TextureView Listener
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        // TextureView?????????????????????
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)
            openCamera()
        }

        // TextureView???????????????????????????
        override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, p1: Int, p2: Int) {}

        // TextureView??????????????????
        override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) {}

        // TextureView??????????????????
        override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?): Boolean {
            return false
        }
    }

    /**
     * ???????????????????????????
     */
    private fun openCamera() {
        /**
         * ?????????????????????????????????
         */
        val manager: CameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            /**
             * ?????????ID?????????
             */
            val cameraId: String = manager.cameraIdList[0]

            /**
             * ???????????????
             */
            manager.openCamera(cameraId, stateCallback, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * ?????????????????????????????????????????????
     */
    private val stateCallback = object : CameraDevice.StateCallback() {

        /**
         * ?????????????????????
         */
        override fun onOpened(cameraDevice: CameraDevice) {
            this@MainActivity.cameraDevice = cameraDevice
            createCameraPreviewSession()
        }

        /**
         * ???????????????
         */
        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraDevice.close()
            this@MainActivity.cameraDevice = null
        }

        /**
         * ??????????????????
         */
        override fun onError(cameraDevice: CameraDevice, error: Int) {
            onDisconnected(cameraDevice)
            finish()
        }
    }

    /**
     * ?????????????????????????????????????????????????????????
     */
    private fun createCameraPreviewSession() {
        try {
            val texture = preview_view.surfaceTexture
            texture.setDefaultBufferSize(preview_view.width, preview_view.height)

            val surface = Surface(texture)
            previewRequestBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(surface)

            cameraDevice?.createCaptureSession(
                Arrays.asList(surface, imageReader.surface),
                @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        try {
                            captureSession = cameraCaptureSession
                            previewRequestBuilder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            previewRequest = previewRequestBuilder.build()
                            cameraCaptureSession.setRepeatingRequest(
                                previewRequest,
                                null,
                                Handler(backgroundThread?.looper)
                            )
                        } catch (e: CameraAccessException) {
                            Log.e("erfs", e.toString())
                        }

                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        //Tools.makeToast(baseContext, "Failed")
                    }
                }, null
            )
        } catch (e: CameraAccessException) {
            Log.e("erf", e.toString())
        }
    }

    /**
     * ?????????????????????????????????????????????
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    private fun setupDigitClassifier() {
        digitClassifier.initialize(loadModelFile())
    }

    override fun onDestroy() {
        digitClassifier.close()
        super.onDestroy()
    }

    private fun classifyDrawing() {
        val bitmap = preview_view?.bitmap

        if ((bitmap != null) && (digitClassifier.isInitialized)) {
            digitClassifier
                .classifyAsync(bitmap)
                .addOnSuccessListener { resultText ->
                    predicted_text.text = resultText
                }
                .addOnFailureListener { e ->
                    predicted_text.text = getString(
                        R.string.tfe_dc_classification_error_message,
                        e.localizedMessage
                    )
                    Log.e(TAG, "Error classifying drawing.", e)
                }
        }
    }

    @Throws(IOException::class)
    private fun loadModelFile(): ByteBuffer {
        val fileDescriptor = assets.openFd(MainActivity.MODEL_FILE)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    companion object {
        private const val TAG = "MainActivity"

        private const val MODEL_FILE = "fashion_mnist.tflite"
    }
}
