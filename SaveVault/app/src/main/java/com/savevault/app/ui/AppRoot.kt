package com.savevault.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }

    AnimatedContent(
        targetState = vm.screen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { screen ->
        when (screen) {
            Screen.Home -> HomeScreen(vm, snackbar)
            is Screen.Detail -> GameDetailScreen(vm, screen.gameId, snackbar)
            is Screen.Edit -> GameEditScreen(vm, screen.gameId, screen.category)
            Screen.Settings -> SettingsScreen(vm, snackbar)
        }
    }
}
