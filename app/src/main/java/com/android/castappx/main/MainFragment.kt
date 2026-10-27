package com.android.castappx.main

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.android.castappx.MainActivity
import com.android.castappx.databinding.FragmentMainBinding
import com.android.castappx.utilOrHelper.PermissionHelper
import com.android.castappx.utilOrHelper.PermissionHelper.checkPermission

class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this).get(MainViewModel::class.java)
    }

    private var videoCapture: VideoCapture<Recorder>? = null
    private var cameraProvider: ProcessCameraProvider? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentMainBinding.inflate(inflater, container, false)

        binding.mainViewModel = viewModel
        binding.lifecycleOwner = viewLifecycleOwner


        viewModel.show_state.observe(viewLifecycleOwner, { newState ->
            when (newState) {
                ShowState.OFF -> {
                    // Handle OFF state
                }

                ShowState.CAMERA_FRONT -> {
                    // Handle CAMERA_FRONT state
                    startCamera(isFrontCamera = true)
                }

                ShowState.CAMERA_BACK -> {
                    // Handle CAMERA_BACK state
                    startCamera(isFrontCamera = false)
                }

                ShowState.PROJECTION_ON -> {
                    // Handle PROJECTION_ON state
                    stopCamera()
                }
            }
        })

        binding.btnSwitchCamera.setOnClickListener {
            val nextState = viewModel.possibleNextState()

            if (nextState in listOf(ShowState.CAMERA_FRONT, ShowState.CAMERA_BACK)
                && !checkPermission(requireContext(), PermissionHelper.CAMERA_PERMISSION)
            ) {
                (requireActivity() as MainActivity).showStackedToast("Camera permission is required")
                return@setOnClickListener
            }

            if (nextState == ShowState.PROJECTION_ON) {
                // TODO: Handle PROJECTION_ON state
            }

            viewModel.show_state.value = nextState
        }

        binding.myResultView.previewStreamState.observe(viewLifecycleOwner) { newState ->
            when (newState) {
                PreviewView.StreamState.IDLE -> {
                    Log.d(TAG, "PreviewView.StreamState.IDLE")
                    binding.myResultView.foreground = Color.BLACK.toDrawable()
                }

                PreviewView.StreamState.STREAMING -> {
                    Log.d(TAG, "PreviewView.StreamState.STREAMING")
                    binding.myResultView.foreground = null
                }
            }
        }




        return binding.root
    }


    private fun startCamera(isFrontCamera: Boolean) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner

            val cameraProvider = cameraProviderFuture.get()
            this.cameraProvider = cameraProvider

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.myResultView.surfaceProvider)
                }

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HIGHEST,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                    )
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)


            val cameraSelector =
                if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    companion object {
        private const val TAG = "MainFragment"
    }
}