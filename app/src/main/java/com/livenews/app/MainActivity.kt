package com.livenews.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.livenews.app.ui.FeedScreen
import com.livenews.app.ui.LiveNewsTheme
import com.livenews.app.ui.NewsViewModel
import com.livenews.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LiveNewsTheme {
                LiveNewsScreens()
            }
        }
    }
}

@Composable
fun LiveNewsScreens() {
    // Built from this activity's own application object rather than through the default factory,
    // which is a process-wide singleton that remembers the first application it ever saw.
    val app = LocalContext.current.applicationContext as LiveNewsApp
    val vm: NewsViewModel = viewModel(factory = viewModelFactory { initializer { NewsViewModel(app) } })
    val settings by vm.settings.collectAsStateWithLifecycle()

    // The "live" part: while the app is on screen, keep fetching on the chosen interval.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, settings.foregroundRefreshMinutes) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.autoRefresh(settings.foregroundRefreshMinutes)
        }
    }

    // Each time the app comes on screen: look for a newer version (every few hours at most), and
    // carry on with an update that was waiting for the user to allow installs.
    LaunchedEffect(lifecycleOwner, settings.autoUpdateCheck) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.onAppVisible(autoCheck = settings.autoUpdateCheck)
        }
    }

    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "feed") {
        composable("feed") {
            FeedScreen(vm, onOpenSettings = { nav.navigate("settings") { launchSingleTop = true } })
        }
        composable("settings") {
            SettingsScreen(vm, onBack = { nav.popBackStack() })
        }
    }
}
