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
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                maxOf(systemBars.bottom, ime.bottom)
            )
            insets
        }


        stackedToast = StackedToast(this)

        if (!allPermissionsGranted(this)) {
            requestPermissions()
        }
    }


    fun showStackedToast(message: CharSequence) {
        stackedToast.show(message)
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
