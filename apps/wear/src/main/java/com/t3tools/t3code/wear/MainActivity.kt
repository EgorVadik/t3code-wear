package com.t3tools.t3code.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.t3tools.t3code.wear.ui.T3WearApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val graph get() = (application as T3WearApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { T3WearApp(graph) }
    }

    override fun onStart() {
        super.onStart()
        // Catch up on environment changes that arrived while the watch app was not running.
        lifecycleScope.launch { graph.syncFromPhone() }
    }
}

/** Builds ViewModels with the app graph without a DI framework. */
class GraphViewModelFactory(private val create: () -> ViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}
