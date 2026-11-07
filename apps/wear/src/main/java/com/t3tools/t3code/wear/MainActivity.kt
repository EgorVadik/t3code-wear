package com.t3tools.t3code.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.t3tools.t3code.wear.ui.T3WearApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val graph get() = (application as T3WearApplication).graph

    /** A thread to open, from tapping the watch-face indicator. */
    private val openThread = MutableStateFlow<Pair<String, String>?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) graph.ongoing.repost()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent { T3WearApp(graph, openThread) }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val environmentId = intent.getStringExtra(EXTRA_ENVIRONMENT_ID) ?: return
        val threadId = intent.getStringExtra(EXTRA_THREAD_ID) ?: return
        openThread.value = environmentId to threadId
    }

    companion object {
        const val EXTRA_ENVIRONMENT_ID = "environmentId"
        const val EXTRA_THREAD_ID = "threadId"
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
