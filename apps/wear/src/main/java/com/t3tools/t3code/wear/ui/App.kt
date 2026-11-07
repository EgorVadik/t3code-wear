package com.t3tools.t3code.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.t3tools.t3code.wear.AppGraph
import com.t3tools.t3code.wear.GraphViewModelFactory
import kotlinx.coroutines.flow.Flow

private object Routes {
    const val HOME = "home"
    const val USAGE = "usage"
    const val ENVIRONMENTS = "environments"
    const val THREAD = "thread/{environmentId}/{threadId}"

    fun thread(environmentId: String, threadId: String) = "thread/$environmentId/$threadId"
}

@Composable
fun T3WearApp(graph: AppGraph) {
    MaterialTheme(colorScheme = T3ColorScheme) {
        AppScaffold {
            val navController = rememberSwipeDismissableNavController()
            SwipeDismissableNavHost(navController = navController, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    val model: HomeViewModel = viewModel(factory = remember { GraphViewModelFactory { HomeViewModel(graph) } })
                    PollWhileVisible(model.polling)
                    HomeScreen(
                        model = model,
                        onOpenThread = { navController.navigate(Routes.thread(it.environmentId, it.threadId)) },
                        onOpenUsage = { navController.navigate(Routes.USAGE) },
                        onOpenEnvironments = { navController.navigate(Routes.ENVIRONMENTS) },
                    )
                }
                composable(Routes.THREAD) { entry ->
                    val environmentId = entry.arguments?.getString("environmentId").orEmpty()
                    val threadId = entry.arguments?.getString("threadId").orEmpty()
                    val model: ThreadViewModel = viewModel(
                        factory = remember(environmentId, threadId) {
                            GraphViewModelFactory { ThreadViewModel(graph, environmentId, threadId) }
                        },
                    )
                    PollWhileVisible(model.polling)
                    ThreadScreen(model)
                }
                composable(Routes.USAGE) {
                    val model: UsageViewModel = viewModel(factory = remember { GraphViewModelFactory { UsageViewModel(graph) } })
                    PollWhileVisible(model.polling)
                    UsageScreen(model)
                }
                composable(Routes.ENVIRONMENTS) {
                    val model: HomeViewModel = viewModel(factory = remember { GraphViewModelFactory { HomeViewModel(graph) } })
                    EnvironmentsScreen(model)
                }
            }
        }
    }
}

/** Polls only while the screen is resumed, so a dimmed or backgrounded watch makes no requests. */
@Composable
private fun PollWhileVisible(polling: Flow<Unit>) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(polling, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { polling.collect {} }
    }
}
