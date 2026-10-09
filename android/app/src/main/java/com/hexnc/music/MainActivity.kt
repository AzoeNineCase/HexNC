package com.hexnc.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hexnc.music.ui.HexNCRoot

/**
 * The single activity.
 *
 * Compose owns every screen, so this class stays deliberately thin: pull the graph off the
 * application, then hand it to the root composable, which resolves the language and palette from
 * persisted settings before it draws anything.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HexNCApp).container
        setContent {
            HexNCRoot(container)
        }
    }
}
