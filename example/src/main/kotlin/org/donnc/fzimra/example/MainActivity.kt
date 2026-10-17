package org.donnc.fzimra.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.donnc.fzimra.example.ui.AppRoot
import org.donnc.fzimra.example.ui.theme.FzimraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val engine = (application as ExampleApp).engine
        setContent { FzimraTheme { AppRoot(engine) } }
    }

    override fun onResume() {
        super.onResume()
        (application as ExampleApp).engine.refreshSoon()
    }
}
