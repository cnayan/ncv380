package com.nc.v380

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nc.v380.ui.CameraApp
import com.nc.v380.ui.theme.NcV380Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NcV380Theme {
                CameraApp()
            }
        }
    }
}
