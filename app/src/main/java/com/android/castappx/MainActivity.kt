package com.android.castappx


import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.android.castappx.selfDesignView.StackedToast
import com.android.castappx.utilOrHelper.PermissionHelper
import com.android.castappx.utilOrHelper.PermissionHelper.allPermissionsGranted

class MainActivity : AppCompatActivity() {


    private lateinit var stackedToast: StackedToast


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }


        stackedToast = StackedToast(this)

        if (!allPermissionsGranted(this)) {
            requestPermissions()
        }
    }


    private fun requestPermissions() {
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissions.entries.forEach { (permission, granted) ->
                if (!granted) {
                    stackedToast.show("缺少权限: $permission")
                }
            }
        }.launch(PermissionHelper.REQUIRED_PERMISSIONS)

    }


    companion object {

    }
}