package com.pocketlm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketlm.app.PocketViewModel

private enum class Screen { Chat, History, Models, Search, Settings, Benchmark, Storage, Compare }

@Composable
fun PocketLmApp(vm: PocketViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
    val error by vm.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val incoming by vm.incomingText.collectAsStateWithLifecycle()

    LaunchedEffect(incoming) {
        if (incoming != null) screen = Screen.Chat
    }

    val askRequests by vm.askRequests.collectAsStateWithLifecycle()
    LaunchedEffect(askRequests) {
        if (askRequests > 0) screen = Screen.Chat
    }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }

    BackHandler(enabled = screen != Screen.Chat) {
        screen = when (screen) {
            Screen.Search -> Screen.Models
            Screen.Benchmark, Screen.Storage, Screen.Compare -> Screen.Settings
            else -> Screen.Chat
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val modifier = Modifier.padding(padding)
        when (screen) {
            Screen.Chat -> ChatScreen(
                vm,
                onOpenHistory = { screen = Screen.History },
                onOpenModels = { screen = Screen.Models },
                onOpenSettings = { screen = Screen.Settings },
                modifier = modifier,
            )
            Screen.History -> HistoryScreen(
                vm,
                onBack = { screen = Screen.Chat },
                onOpened = { screen = Screen.Chat },
                modifier = modifier,
            )
            Screen.Models -> ModelsScreen(
                vm,
                onBack = { screen = Screen.Chat },
                onOpenSearch = { screen = Screen.Search },
                onModelLoaded = { screen = Screen.Chat },
                modifier = modifier,
            )
            Screen.Search -> SearchScreen(vm, onBack = { screen = Screen.Models }, modifier = modifier)
            Screen.Settings -> SettingsScreen(
                vm,
                onBack = { screen = Screen.Chat },
                onOpenBenchmark = { screen = Screen.Benchmark },
                onOpenStorage = { screen = Screen.Storage },
                onOpenCompare = { screen = Screen.Compare },
                modifier = modifier,
            )
            Screen.Compare -> CompareScreen(vm, onBack = { screen = Screen.Settings }, modifier = modifier)
            Screen.Benchmark -> BenchmarkScreen(vm, onBack = { screen = Screen.Settings }, modifier = modifier)
            Screen.Storage -> StorageScreen(
                vm,
                onBack = { screen = Screen.Settings },
                onOpenModels = { screen = Screen.Models },
                modifier = modifier,
            )
        }
    }
}
