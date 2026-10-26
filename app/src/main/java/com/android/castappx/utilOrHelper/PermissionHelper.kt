package com.android.castappx.utilOrHelper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionHelper {

    const val CAMERA_PERMISSION = Manifest.permission.CAMERA
    const val RECORD_AUDIO_PERMISSION = Manifest.permission.RECORD_AUDIO
    const val WRITE_EXTERNAL_STORAGE_PERMISSION_LE28 = Manifest.permission.WRITE_EXTERNAL_STORAGE


    val REQUIRED_PERMISSIONS = mutableListOf(
        CAMERA_PERMISSION,
        RECORD_AUDIO_PERMISSION
    ).apply {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(WRITE_EXTERNAL_STORAGE_PERMISSION_LE28)
        }
    }.toTypedArray()



    fun allPermissionsGranted(context: Context) = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }




}