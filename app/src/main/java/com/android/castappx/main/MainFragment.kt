package com.android.castappx.main

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Matrix
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.android.castappx.MainActivity
import com.android.castappx.R
import com.android.castappx.databinding.FragmentMainBinding
import com.android.castappx.utilOrHelper.PermissionHelper
import com.android.castappx.utilOrHelper.PermissionHelper.checkPermission

class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel by lazy { ViewModelProvider(this)[MainViewModel::class.java] }

    private var microphone: com.android.castappx.audio.AudioCapture? = null
    private var resumed = false
    private var cameraReady = false

    private var cameraProvider: ProcessCameraProvider? = null

    private var projectionService: MyMediaProjectionService? = null
    private var serviceBound = false
    private var cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var deviceDialog: AlertDialog? = null
    private var remoteSurface: android.view.Surface? = null

    private val projectionPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (viewModel.socketChat.serverRunning.value == true) {
                viewModel.projectionPending = false
                updateControls()
                return@registerForActivityResult
            }
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                try {
                    ContextCompat.startForegroundService(
                        requireContext(), serviceIntent()
                            .setAction(MyMediaProjectionService.ACTION_START)
                            .putExtra(MyMediaProjectionService.EXTRA_CONSENT, result.data)
                    )
                } catch (error: RuntimeException) {
                    viewModel.projectionPending = false
                    updateControls()
                    showMessage(R.string.projection_failed)
                    Log.e(TAG, "Unable to start projection service", error)
                }
            } else {
                viewModel.projectionPending = false
                updateControls()
                showMessage(R.string.projection_cancelled)
            }
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as MyMediaProjectionService.LocalBinder).service
            projectionService = service
            val chat = viewModel.socketChat
            service.onFrame = { bitmap -> chat.sendFrame(bitmap) }
            service.onAudio = { bytes, size -> chat.sendAudio(bytes, size) }
            service.audioError.observe(viewLifecycleOwner) { failed ->
                if (failed) showMessage(R.string.device_audio_failed)
            }
            service.frame.observe(viewLifecycleOwner) { bitmap ->
                if (viewModel.show_state.value == ShowState.PROJECTION_ON) binding.screenPreview.setImageBitmap(
                    bitmap
                )
            }
            if (chat.serverRunning.value == true) service.stopCapture()
            service.error.observe(viewLifecycleOwner) { failed ->
                if (failed) {
                    viewModel.projectionPending = false
                    updateControls()
                    showMessage(R.string.projection_failed)
                }
            }
            service.active.observe(viewLifecycleOwner) { active ->
                if (active && chat.serverRunning.value == true) {
                    service.stopCapture()
                } else if (active) {
                    viewModel.projectionPending = false
                    viewModel.show_state.value = ShowState.PROJECTION_ON
                } else if (viewModel.show_state.value == ShowState.PROJECTION_ON) {
                    viewModel.show_state.value = ShowState.OFF
                }
                updateControls()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            projectionService = null
            viewModel.projectionPending = false
            if (viewModel.show_state.value == ShowState.PROJECTION_ON) viewModel.show_state.value =
                ShowState.OFF
            updateControls()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.mainViewModel = viewModel
        binding.lifecycleOwner = viewLifecycleOwner
        if (cameraExecutor.isShutdown) cameraExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor()
        setupSocketChat()
        binding.remotePreview.surfaceTextureListener =
            object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    texture: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    remoteSurface = android.view.Surface(texture)
                    viewModel.socketChat.setRemoteSurface(remoteSurface)
                    updateRemoteTransform()
                }

                override fun onSurfaceTextureSizeChanged(
                    texture: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    updateRemoteTransform()
                }

                override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) =
                    Unit

                override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
                    viewModel.socketChat.setRemoteSurface(null)
                    remoteSurface?.release(); remoteSurface = null
                    return true
                }
            }

        viewModel.show_state.observe(viewLifecycleOwner) { state ->
            val projecting = state == ShowState.PROJECTION_ON
            binding.screenPreview.visibility = if (projecting) View.VISIBLE else View.GONE
            val camera = state == ShowState.CAMERA_FRONT || state == ShowState.CAMERA_BACK
            viewModel.socketChat.setCaptureActive(camera || projecting)
            binding.myResultView.visibility = if (camera) View.VISIBLE else View.GONE
            binding.remotePreview.visibility =
                if (state == ShowState.RECEIVING_FRAME || state == ShowState.WAITING_FOR_FRAME) View.VISIBLE else View.GONE
            binding.remotePreview.alpha = if (state == ShowState.RECEIVING_FRAME) 1f else 0f
            if (!projecting) binding.screenPreview.setImageDrawable(null)
            when (state) {
                ShowState.CAMERA_FRONT -> startCamera(true)
                ShowState.CAMERA_BACK -> startCamera(false)
                else -> stopCamera()
            }
            updateControls()
        }
        binding.btnSwitchCamera.setOnClickListener {
            if (viewModel.projectionPending || viewModel.socketChat.serverRunning.value == true) return@setOnClickListener
            val next = viewModel.possibleNextState()
            if (next == ShowState.CAMERA_FRONT || next == ShowState.CAMERA_BACK) {
                if (!checkPermission(requireContext(), PermissionHelper.CAMERA_PERMISSION)) {
                    showMessage(R.string.camera_permission_required)
                    return@setOnClickListener
                }
            }
            if (viewModel.show_state.value == ShowState.PROJECTION_ON) projectionService?.stopCapture()
            viewModel.show_state.value = next
        }
        binding.btnScreenRecording.setOnClickListener {
            if (viewModel.projectionPending || viewModel.socketChat.serverRunning.value == true) return@setOnClickListener
            if (viewModel.show_state.value == ShowState.PROJECTION_ON) {
                projectionService?.stopCapture()
            } else {
                requestProjection()
            }
        }
        binding.myResultView.previewStreamState.observe(viewLifecycleOwner) { state ->
            binding.myResultView.foreground =
                if (state == PreviewView.StreamState.STREAMING) null else Color.BLACK.toDrawable()
        }
        serviceBound =
            requireContext().bindService(serviceIntent(), connection, Context.BIND_AUTO_CREATE)
    }

    private fun requestProjection() {
        if (!checkPermission(requireContext(), PermissionHelper.RECORD_AUDIO_PERMISSION)) {
            showMessage(R.string.audio_permission_required)
            return
        }
        if (android.os.Build.VERSION.SDK_INT < 29) showMessage(R.string.device_audio_unsupported)
        viewModel.projectionPending = true
        updateControls()
        val manager = requireContext().getSystemService(MediaProjectionManager::class.java)
        projectionPermission.launch(manager.createScreenCaptureIntent())
    }

    private fun setupSocketChat() {
        val chat = viewModel.socketChat
        binding.btnStartSocketServer.setOnClickListener {
            if (chat.serverRunning.value == true) chat.disconnect() else chat.startServer()
        }
        binding.btnConnectToSocketServer.setOnClickListener {
            if (chat.connected.value == true || chat.connecting.value == true) chat.disconnect()
            else showDeviceDialog()
        }
        binding.btnMuteAudio.setOnClickListener { chat.toggleMute() }
        chat.muted.observe(viewLifecycleOwner) { updateSocketControls() }
        chat.mediaReady.observe(viewLifecycleOwner) { updateMicrophone() }
        binding.btnSendMessage.setOnClickListener { chat.send(binding.messageInput.text.toString()) }
        binding.messageInput.doAfterTextChanged { updateSocketControls() }
        chat.status.observe(viewLifecycleOwner) { binding.socketStatus.text = it }
        chat.serverRunning.observe(viewLifecycleOwner) { running ->
            if (running) {
                viewModel.projectionPending = false
                projectionService?.stopCapture()
                viewModel.show_state.value =
                    if (chat.remoteFrame.value != true) ShowState.WAITING_FOR_FRAME else ShowState.RECEIVING_FRAME
            } else if (viewModel.show_state.value in listOf(
                    ShowState.WAITING_FOR_FRAME,
                    ShowState.RECEIVING_FRAME
                )
            ) {
                viewModel.show_state.value = ShowState.OFF
            }
            updateControls()
            updateSocketControls()
        }
        chat.remoteFrame.observe(viewLifecycleOwner) { hasFrame ->
            if (chat.serverRunning.value == true) {
                val next =
                    if (!hasFrame) ShowState.WAITING_FOR_FRAME else ShowState.RECEIVING_FRAME
                if (viewModel.show_state.value != next) viewModel.show_state.value = next
            }
        }
        chat.remoteVideoSize.observe(viewLifecycleOwner) { updateRemoteTransform() }
        chat.remoteStalled.observe(viewLifecycleOwner) { updateControls() }
        chat.connected.observe(viewLifecycleOwner) { updateSocketControls() }
        chat.connecting.observe(viewLifecycleOwner) { updateSocketControls() }
        chat.sending.observe(viewLifecycleOwner) { updateSocketControls() }
        var lastSentSequence = chat.sentSequence.value
        chat.sentSequence.observe(viewLifecycleOwner) {
            if (it != lastSentSequence) binding.messageInput.text.clear()
            lastSentSequence = it
        }
        chat.messages.observe(viewLifecycleOwner) { messages ->
            val scroll = binding.chatScroll
            val followLatest = !scroll.canScrollVertically(1)
            binding.chatMessages.text = if (messages.isEmpty()) getString(R.string.chat_empty)
            else messages.joinToString("\n\n")
            if (followLatest) scroll.post {
                if (_binding != null) scroll.smoothScrollTo(0, scroll.getChildAt(0).height)
            }
        }
    }

    private fun updateRemoteTransform() {
        val preview = _binding?.remotePreview ?: return
        val size = viewModel.socketChat.remoteVideoSize.value ?: return
        if (preview.width == 0 || preview.height == 0) return
        val scale =
            minOf(preview.width.toFloat() / size.width, preview.height.toFloat() / size.height)
        preview.setTransform(Matrix().apply {
            setScale(
                size.width * scale / preview.width, size.height * scale / preview.height,
                preview.width / 2f, preview.height / 2f
            )
        })
    }

    private fun updateSocketControls() {
        val current = _binding ?: return
        val chat = viewModel.socketChat
        current.btnStartSocketServer.setText(if (chat.serverRunning.value == true) R.string.stop_socket_server else R.string.start_socket_server)
        current.btnStartSocketServer.isEnabled =
            chat.connecting.value != true && (chat.connected.value != true || chat.serverRunning.value == true)
        current.btnConnectToSocketServer.setText(
            when {
                chat.connecting.value == true -> R.string.cancel_socket_connection
                chat.connected.value == true -> R.string.disconnect_socket
                else -> R.string.connect_to_socket_server
            }
        )
        current.btnMuteAudio.visibility =
            if (chat.serverRunning.value == true) View.VISIBLE else View.GONE
        current.btnMuteAudio.isEnabled = chat.connected.value == true
        current.btnMuteAudio.setText(if (chat.muted.value == true) R.string.unmute_audio else R.string.mute_audio)
        current.messageInput.isEnabled = chat.sending.value != true
        current.btnSendMessage.isEnabled =
            chat.connected.value == true && chat.sending.value != true && !current.messageInput.text.isNullOrBlank()
    }

    private fun showDeviceDialog() {
        if (deviceDialog != null) return
        val chat = viewModel.socketChat
        val context = requireContext()
        val padding = (16 * resources.displayMetrics.density).toInt()
        val searchStatus =
            TextView(context).apply { setPadding(padding, padding, padding, padding) }
        val adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_1)
        val list = ListView(context).apply { this.adapter = adapter }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchStatus)
            addView(
                list,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (240 * resources.displayMetrics.density).toInt()
                )
            )
        }
        val deviceObserver =
            androidx.lifecycle.Observer<List<android.net.nsd.NsdServiceInfo>> { devices ->
                adapter.clear()
                adapter.addAll(devices.map { it.serviceName })
                adapter.notifyDataSetChanged()
            }
        val statusObserver = androidx.lifecycle.Observer<String> { searchStatus.text = it }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.select_socket_device)
            .setView(content)
            .setNegativeButton(R.string.close_device_search, null)
            .create()
        deviceDialog = dialog
        list.setOnItemClickListener { _, _, position, _ ->
            chat.devices.value?.getOrNull(position)?.let { device ->
                dialog.dismiss()
                chat.connect(device)
            }
        }
        dialog.setOnDismissListener {
            chat.stopDiscovery()
            chat.devices.removeObserver(deviceObserver)
            chat.discoveryStatus.removeObserver(statusObserver)
            deviceDialog = null
        }
        chat.startDiscovery()
        chat.devices.observe(viewLifecycleOwner, deviceObserver)
        chat.discoveryStatus.observe(viewLifecycleOwner, statusObserver)
        dialog.show()
    }

    private fun updateControls() {
        val current = _binding ?: return
        val state = viewModel.show_state.value
        current.previewContainer.keepScreenOn = state != null && state != ShowState.OFF
        current.btnSwitchCamera.isEnabled =
            !viewModel.projectionPending && viewModel.socketChat.serverRunning.value != true
        current.btnScreenRecording.isEnabled =
            !viewModel.projectionPending && viewModel.socketChat.serverRunning.value != true
        current.btnScreenRecording.setText(if (viewModel.show_state.value == ShowState.PROJECTION_ON) R.string.stop_projection else R.string.start_projection)
        current.previewStatus.setText(
            if (viewModel.projectionPending) R.string.projection_waiting else when (viewModel.show_state.value) {
                ShowState.CAMERA_FRONT -> R.string.preview_front_camera
                ShowState.CAMERA_BACK -> R.string.preview_back_camera
                ShowState.PROJECTION_ON -> R.string.preview_projection
                ShowState.WAITING_FOR_FRAME -> R.string.preview_waiting_for_frame
                ShowState.RECEIVING_FRAME -> if (viewModel.socketChat.remoteStalled.value == true) R.string.preview_frame_stalled else R.string.preview_receiving_frame
                else -> R.string.preview_off
            }
        )
    }

    private fun startCamera(isFrontCamera: Boolean) {
        val context = requireContext()
        val requestedState = if (isFrontCamera) ShowState.CAMERA_FRONT else ShowState.CAMERA_BACK
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val current = _binding ?: return@addListener
            if (viewModel.show_state.value != requestedState) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                // PreviewView mirrors the front camera by default; cancel that so the
                // local preview matches the unmirrored ImageAnalysis frames we send.
                // COMPATIBLE mode keeps this view transform on the TextureView path.
                current.myResultView.scaleX = if (isFrontCamera) -1f else 1f
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(current.myResultView.surfaceProvider) }
                val selector =
                    if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                val chat = viewModel.socketChat
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                var lastFrameAt = 0L
                analysis.setAnalyzer(cameraExecutor) { image ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    var rotation = 0
                    val source = try {
                        if (!chat.canSendFrames() || now - lastFrameAt < com.android.castappx.socket.VideoProtocol.FRAME_INTERVAL_MS) {
                            return@setAnalyzer
                        }
                        lastFrameAt = now
                        rotation = image.imageInfo.rotationDegrees
                        image.toBitmap()
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "Unable to copy camera frame", error)
                        null
                    } finally {
                        // Release the camera buffer BEFORE JPEG encoding and socket IO.
                        image.close()
                    } ?: return@setAnalyzer
                    var rotated: android.graphics.Bitmap? = null
                    try {
                        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                        rotated = android.graphics.Bitmap.createBitmap(
                            source, 0, 0, source.width, source.height, matrix, true
                        )
                        chat.sendFrame(rotated)
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "Unable to send camera frame", error)
                    } finally {
                        if (rotated !== source) rotated?.recycle()
                        source.recycle()
                    }
                }
                provider.bindToLifecycle(viewLifecycleOwner, selector, preview, analysis)
                cameraReady = true
                updateMicrophone()
            } catch (error: Exception) {
                Log.e(TAG, "Unable to bind camera", error)
                viewModel.show_state.value = ShowState.OFF
                showMessage(R.string.camera_failed)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun stopCamera() {
        cameraReady = false
        microphone?.close()
        microphone = null
        cameraProvider?.unbindAll()
        _binding?.myResultView?.foreground = Color.BLACK.toDrawable()
    }

    private fun updateMicrophone() {
        val shouldRecord = resumed && cameraReady && viewModel.socketChat.canSendFrames()
        if (!shouldRecord) {
            microphone?.close()
            microphone = null
        } else if (microphone == null) {
            if (!checkPermission(requireContext(), PermissionHelper.RECORD_AUDIO_PERMISSION)) {
                showMessage(R.string.audio_permission_required)
                return
            }
            try {
                val chat = viewModel.socketChat
                microphone = com.android.castappx.audio.AudioCapture.start(
                    onError = { activity?.runOnUiThread { if (_binding != null) showMessage(R.string.microphone_failed) } },
                    onPcm = { bytes, size -> chat.sendAudio(bytes, size) })
            } catch (error: Exception) {
                Log.w(TAG, "Unable to capture microphone", error)
                showMessage(R.string.microphone_failed)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateMicrophone()
    }

    override fun onPause() {
        resumed = false
        updateMicrophone()
        super.onPause()
    }

    private fun serviceIntent() = Intent(requireContext(), MyMediaProjectionService::class.java)
    private fun showMessage(message: Int) {
        (activity as? MainActivity)?.showStackedToast(getString(message))
    }

    override fun onDestroyView() {
        deviceDialog?.dismiss()
        viewModel.socketChat.setRemoteSurface(null)
        remoteSurface?.release(); remoteSurface = null
        stopCamera()
        cameraExecutor.shutdownNow()
        if (serviceBound) requireContext().unbindService(connection)
        serviceBound = false
        projectionService = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "MainFragment"
    }
}
