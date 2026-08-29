package com.depthmaker.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.depthmaker.app.ui.AppViewModel
import com.depthmaker.app.ui.HomeScreen
import com.depthmaker.app.ui.ProcessingScreen
import com.depthmaker.app.ui.ResultScreen
import com.depthmaker.app.ui.Screen
import com.depthmaker.app.ui.SettingsScreen
import com.depthmaker.app.ui.theme.DepthMakerTheme
import com.depthmaker.app.util.MediaStoreSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DepthMakerTheme { AppRoot() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val vm: AppViewModel = viewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* denied only means no progress notification; the job still runs */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.onVideoPicked(uri) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }

    var savedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            val res = snackbar.showSnackbar(message = it, actionLabel = if (savedUri != null) "Open" else null)
            if (res == SnackbarResult.ActionPerformed) {
                savedUri?.let { uri ->
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    )
                }
            }
            vm.dismissNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("DepthMaker") },
                actions = {
                    IconButton(onClick = {
                        if (state.screen == Screen.Settings) vm.closeSettings() else vm.openSettings()
                    }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = com.depthmaker.app.ui.theme.BackgroundColor,
                    titleContentColor = com.depthmaker.app.ui.theme.TextPrimary,
                    actionIconContentColor = com.depthmaker.app.ui.theme.TextPrimary
                )
            )
        },
        containerColor = com.depthmaker.app.ui.theme.BackgroundColor
    ) { inner ->
        when (state.screen) {
            Screen.Settings -> SettingsScreen(
                settings = state.settings,
                onModel = vm::setModel,
                onFormat = vm::setFormat,
                onServerUrl = vm::setServerUrl,
                onToken = vm::setToken,
                onDone = vm::closeSettings,
                modifier = Modifier.padding(inner)
            )
            Screen.Processing -> ProcessingScreen(
                meta = state.meta,
                progress = state.progress,
                stage = state.stage,
                etaSeconds = state.etaSeconds,
                onCancel = vm::cancelJob,
                modifier = Modifier.padding(inner)
            )
            Screen.Result -> state.result?.let { r ->
                ResultScreen(
                    result = r,
                    onSave = {
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) {
                                MediaStoreSaver.saveToGallery(context, r.file, r.originalName, r.mime)
                            }
                            savedUri = uri
                            vm.showNotice(
                                if (uri != null) "Saved to Gallery" else "Save nahi ho paya."
                            )
                        }
                    },
                    onNewVideo = vm::newVideo,
                    modifier = Modifier.padding(inner)
                )
            }
            Screen.Home -> HomeScreen(
                meta = state.meta,
                onPick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
                onClear = vm::clearSelection,
                onCreate = vm::startJob,
                modifier = Modifier.padding(inner)
            )
        }
    }
}
