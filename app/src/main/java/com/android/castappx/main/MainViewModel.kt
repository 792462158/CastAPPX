package com.android.castappx.main

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel: ViewModel() {

    val show_state = MutableLiveData(ShowState.OFF)

    fun possibleNextState(): ShowState {
        val currentState = show_state.value
        val nextStates = listOf(
            ShowState.OFF,
            ShowState.CAMERA_FRONT,
            ShowState.CAMERA_BACK,
            ShowState.PROJECTION_ON
        )
        val nextState = nextStates[(nextStates.indexOf(currentState) + 1) % nextStates.size]
        return nextState
    }

}

enum class ShowState {
    OFF,    // 未开启
    CAMERA_FRONT,  // 前置摄像头
    CAMERA_BACK,   // 后置摄像头
    PROJECTION_ON // 屏幕录制中
}