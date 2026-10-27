package com.android.castappx.main

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.CameraSelector
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

    private var cameraProvider: ProcessCameraProvider? = null

    private var projectionService: MyMediaProjectionService? = null
    private var serviceBound = false
    private var previewSurface: Surface? = null
    private var deviceDialog: AlertDialog? = null

    private val projectionPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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
            service.captureSize.observe(viewLifecycleOwner) { updateProjectionSurface() }
            service.error.observe(viewLifecycleOwner) { failed ->
                if (failed) {
                    viewModel.projectionPending = false
                    updateControls()
                    showMessage(R.string.projection_failed)
                }
            }
            service.active.observe(viewLifecycleOwner) { active ->
                if (active) {
                    viewModel.projectionPending = false
                    viewModel.show_state.value = ShowState.PROJECTION_ON
                } else if (viewModel.show_state.value == ShowState.PROJECTION_ON) {
                    viewModel.show_state.value = ShowState.OFF
                }
                updateControls()
            }
            updateProjectionSurface()
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
        setupSocketChat()

        binding.screenPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                texture: SurfaceTexture,
                width: Int,
                height: Int
            ) = updateProjectionSurface()

            override fun onSurfaceTextureSizeChanged(
                texture: SurfaceTexture,
                width: Int,
                height: Int
            ) = updateProjectionSurface()

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                releasePreviewSurface()
                return true
            }
        }
        viewModel.show_state.observe(viewLifecycleOwner) { state ->
            val projecting = state == ShowState.PROJECTION_ON
            binding.screenPreview.visibility = if (projecting) View.VISIBLE else View.GONE
            binding.myResultView.visibility = if (projecting) View.GONE else View.VISIBLE
            when (state) {
                ShowState.CAMERA_FRONT -> startCamera(true)
                ShowState.CAMERA_BACK -> startCamera(false)
                ShowState.OFF, ShowState.PROJECTION_ON -> stopCamera()
            }
            updateControls()
        }
        binding.btnSwitchCamera.setOnClickListener {
            if (viewModel.projectionPending) return@setOnClickListener
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
            if (viewModel.projectionPending) return@setOnClickListener
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
        binding.btnSendMessage.setOnClickListener { chat.send(binding.messageInput.text.toString()) }
        binding.messageInput.doAfterTextChanged { updateSocketControls() }
        chat.status.observe(viewLifecycleOwner) { binding.socketStatus.text = it }
        chat.serverRunning.observe(viewLifecycleOwner) { updateSocketControls() }
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
        current.btnSwitchCamera.isEnabled = !viewModel.projectionPending
        current.btnScreenRecording.isEnabled = !viewModel.projectionPending
        current.btnScreenRecording.setText(if (viewModel.show_state.value == ShowState.PROJECTION_ON) R.string.stop_projection else R.string.start_projection)
        current.previewStatus.setText(
            if (viewModel.projectionPending) R.string.projection_waiting else when (viewModel.show_state.value) {
                ShowState.CAMERA_FRONT -> R.string.preview_front_camera
                ShowState.CAMERA_BACK -> R.string.preview_back_camera
                ShowState.PROJECTION_ON -> R.string.preview_projection
                else -> R.string.preview_off
            }
        )
    }

    private fun updateProjectionSurface() {
        val textureView = _binding?.screenPreview ?: return
        val texture = textureView.surfaceTexture ?: return
        val size = projectionService?.captureSize?.value ?: return
        if (textureView.width == 0 || textureView.height == 0) return
        projectionService?.setSurface(null)
        previewSurface?.release()
        texture.setDefaultBufferSize(size.width, size.height)
        previewSurface = Surface(texture)
        projectionService?.setSurface(previewSurface)
        val scale = minOf(
            textureView.width.toFloat() / size.width,
            textureView.height.toFloat() / size.height
        )
        textureView.setTransform(Matrix().apply {
            setScale(
                size.width * scale / textureView.width, size.height * scale / textureView.height,
                textureView.width / 2f, textureView.height / 2f
            )
        })
    }

    private fun releasePreviewSurface() {
        projectionService?.setSurface(null)
        previewSurface?.release()
        previewSurface = null
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
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(current.myResultView.surfaceProvider) }
                val selector =
                    if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(viewLifecycleOwner, selector, preview)
            } catch (error: Exception) {
                Log.e(TAG, "Unable to bind camera", error)
                viewModel.show_state.value = ShowState.OFF
                showMessage(R.string.camera_failed)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        _binding?.myResultView?.foreground = Color.BLACK.toDrawable()
    }

    private fun serviceIntent() = Intent(requireContext(), MyMediaProjectionService::class.java)
    private fun showMessage(message: Int) {
        (activity as? MainActivity)?.showStackedToast(getString(message))
    }

    override fun onDestroyView() {
        deviceDialog?.dismiss()
        stopCamera()
        releasePreviewSurface()
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
