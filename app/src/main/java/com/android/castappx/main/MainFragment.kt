package com.android.castappx.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        binding.mainViewModel = viewModel


        viewModel.show_state.observe(viewLifecycleOwner, { newState ->
            when (newState) {
                ShowState.OFF -> {
                    // Handle OFF state
                }

                ShowState.CAMERA_FRONT -> {
                    // Handle CAMERA_FRONT state
                }

                ShowState.CAMERA_BACK -> {
                    // Handle CAMERA_BACK state
                }

                ShowState.PROJECTION_ON -> {
                    // Handle PROJECTION_ON state
                }
            }
        })

        binding.btnSwitchCamera.setOnClickListener {
            val possibleNextState = viewModel.possibleNextState()

            if (possibleNextState == ShowState.CAMERA_FRONT || possibleNextState == ShowState.CAMERA_BACK) {
                if (!checkPermission(requireContext(), PermissionHelper.CAMERA_PERMISSION)) {
                    (requireActivity() as MainActivity).showStackedToast("Camera permission is required")
                    return@setOnClickListener
                }
            }

        }


        return binding.root
    }

    companion object {

    }
}