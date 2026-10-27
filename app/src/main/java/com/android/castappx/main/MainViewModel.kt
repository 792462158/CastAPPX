package com.android.castappx.main

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel: ViewModel() {

    val show_state = MutableLiveData(ShowState.OFF)


    var projectionPending = false

    // Screen capture is controlled by its own button, outside the camera cycle.
    fun possibleNextState(): ShowState = when (show_state.value) {
        ShowState.CAMERA_FRONT -> ShowState.CAMERA_BACK
        ShowState.CAMERA_BACK -> ShowState.OFF
        else -> ShowState.CAMERA_FRONT
    }


}

enum class ShowState {
    OFF,    // 未开启
    CAMERA_FRONT,  // 前置摄像头
    CAMERA_BACK,   // 后置摄像头
    PROJECTION_ON // 屏幕录制中
}