package org.donnc.fzimra.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.donnc.fzimra.example.ui.AppRoot
import org.donnc.fzimra.example.ui.theme.FzimraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val engine = (application as ExampleApp).engine
        // ZIMRA expects a device in use to report itself online.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { engine.pingWhileOpen() } }
        setContent {
            FzimraTheme {
                AppRoot(engine)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as ExampleApp).engine.refreshSoon()
    }
}
