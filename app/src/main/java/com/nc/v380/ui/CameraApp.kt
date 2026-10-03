package com.nc.v380.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nc.v380.camera.CameraCredentials
import com.nc.v380.camera.CameraSettings
import com.nc.v380.camera.CameraStore
import com.nc.v380.camera.LiveController
import com.nc.v380.ui.theme.NcV380Theme

@Composable
fun CameraApp(cameraStore: CameraStore? = null) {
    val context = LocalContext.current
    val store = remember(cameraStore) { cameraStore ?: CameraStore(context) }
    var camera by remember { mutableStateOf(store.settings()) }
    var live by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    BackHandler(enabled = live) { live = false }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (live) key(camera) {
                LiveScreen(camera, store, onBack = { live = false }, onSettings = { editing = true })
            } else {
                CameraList(camera, onOpen = { live = true }, onSettings = { editing = true })
            }
        }
    }
    if (editing) CameraSettingsDialog(camera, onDismiss = { editing = false }, onSave = {
        store.saveSettings(it)
        camera = it
        editing = false
    })
}

@Composable
private fun CameraList(camera: CameraSettings, onOpen: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(12.dp)) {
                Text("NC", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text("NC Cameras", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(44.dp))
        Text("Your cameras", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("A live view, wherever you are at home.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("MY DEVICES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("1 camera", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(12.dp))
        Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("camera-card"),
            shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(22.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        CameraGlyph(Modifier.padding(18.dp).size(40.dp), MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(camera.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(camera.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onSettings) { Text("Camera settings") }
                    TextButton(onClick = onOpen) { Text("Watch live  →", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape = RoundedCornerShape(16.dp)) {
            Text("Keep your phone and camera on the same Wi-Fi to watch live video and hear camera audio.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(18.dp))
        }
    }
}

@Composable
private fun LiveScreen(camera: CameraSettings, store: CameraStore, onBack: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { LiveController(context, store) }
    var showLogin by remember { mutableStateOf(!controller.hasLogin) }
    var networkAllowed by remember { mutableStateOf(localNetworkAllowed(context)) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        networkAllowed = granted
        permissionDenied = !granted
        controller.setNetworkAllowed(granted)
    }
    DisposableEffect(controller, lifecycleOwner) {
        fun resume() {
            networkAllowed = localNetworkAllowed(context)
            controller.setNetworkAllowed(networkAllowed)
            controller.setActive(true)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) resume()
            if (event == Lifecycle.Event.ON_STOP) controller.setActive(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) resume()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); controller.close() }
    }
    val state = controller.state
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("live-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to cameras" }) {
                Text("‹", fontSize = 32.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(camera.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(camera.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(state.aspectRatio)
            .background(Color(0xFF091417), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            AndroidView(factory = { viewContext ->
                SurfaceView(viewContext).apply {
                    keepScreenOn = true
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = controller.attachSurface(holder.surface)
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) = controller.detachSurface()
                    })
                }
            }, modifier = Modifier.fillMaxSize())
            if (!state.videoPlaying) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                if (state.connecting) CircularProgressIndicator(Modifier.size(32.dp), color = Color(0xFF72DED3), strokeWidth = 3.dp)
                else CameraGlyph(Modifier.size(48.dp), Color(0xFF789A9D))
                Spacer(Modifier.height(16.dp))
                Text(if (state.error) "Unable to connect" else if (state.connecting) "Connecting" else "Live view",
                    color = Color.White, style = MaterialTheme.typography.titleMedium)
                if (state.error) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.message, color = Color(0xFFB6CBCE), style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            if (state.videoPlaying) Surface(modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                shape = RoundedCornerShape(8.dp), color = Color(0xDD006B64)) {
                Text("●  LIVE", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        Column(Modifier.padding(24.dp)) {
            Text("Live view", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(state.message, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Camera audio", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(if (controller.muted) "Muted" else state.audioMessage,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = { controller.toggleMute() }, modifier = Modifier.testTag("mute-audio")) {
                        Text(if (controller.muted) "Unmute" else "Mute")
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            when {
                !controller.hasLogin -> Button(onClick = { showLogin = true }, modifier = Modifier.fillMaxWidth()) { Text("Connect camera") }
                !networkAllowed -> Button(onClick = {
                    if (permissionDenied) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    else if (Build.VERSION.SDK_INT >= 37) permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }, modifier = Modifier.fillMaxWidth()) { Text(if (permissionDenied) "Open app permissions" else "Allow local network access") }
                else -> OutlinedButton(onClick = { controller.reconnect() }, enabled = !state.connecting, modifier = Modifier.fillMaxWidth()) { Text("Reconnect") }
            }
            TextButton(onClick = { showLogin = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Camera login") }
            Spacer(Modifier.height(8.dp))
            Text("Use your phone's volume buttons to adjust camera sound.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showLogin) CameraLoginDialog(camera, initialUsername = store.credentials()?.username ?: "admin",
        onDismiss = { showLogin = false }, onConnect = { credentials, remember ->
            val error = controller.connect(credentials, remember)
            if (error == null) {
                showLogin = false
                if (!networkAllowed && Build.VERSION.SDK_INT >= 37) permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
            error
        })
}

private fun localNetworkAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 37 ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

@Composable
private fun CameraLoginDialog(camera: CameraSettings, initialUsername: String, onDismiss: () -> Unit,
    onConnect: (CameraCredentials, Boolean) -> String?) {
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberLogin by remember { mutableStateOf(true) }
    var noPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Camera login") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Enter the device login for ${camera.name}. Use the camera password you set in V380.")
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it; noPassword = false }, label = { Text("Camera password") }, singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton(onClick = { passwordVisible = !passwordVisible }) { Text(if (passwordVisible) "Hide" else "Show") } })
            if (password.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(noPassword, { noPassword = it })
                Text("This camera has no password", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(rememberLogin, { rememberLogin = it })
                Text("Remember on this phone", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        TextButton(enabled = password.isNotEmpty() || noPassword, onClick = {
            val login = CameraCredentials(username.trim(), password)
            error = login.validationError(camera.olderCamera) ?: onConnect(login, rememberLogin)
        }) { Text("Connect") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } })
}

@Composable
private fun CameraSettingsDialog(initial: CameraSettings, onDismiss: () -> Unit, onSave: (CameraSettings) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var address by remember { mutableStateOf(initial.address) }
    var id by remember { mutableStateOf(initial.deviceId) }
    var older by remember { mutableStateOf(initial.olderCamera) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Camera settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Camera name") }, singleLine = true)
            OutlinedTextField(address, { address = it }, label = { Text("IP address") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(id, { id = it }, label = { Text("Device ID (optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), supportingText = { Text("Leave blank to find it automatically.") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(older, { older = it })
                Text("Older-camera compatibility", style = MaterialTheme.typography.bodySmall)
            }
            Text("Try compatibility mode if the camera rejects a connection with the correct login.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val updated = CameraSettings(name.trim(), address.trim(), id.trim(), older)
            error = updated.validationError()
            if (error == null) onSave(updated)
        }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun CameraGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.width * 0.06f)
        drawRoundRect(color, Offset(size.width * 0.08f, size.height * 0.28f), Size(size.width * 0.84f, size.height * 0.57f), CornerRadius(size.width * 0.1f), style = stroke)
        drawRoundRect(color, Offset(size.width * 0.31f, size.height * 0.12f), Size(size.width * 0.38f, size.height * 0.17f), CornerRadius(size.width * 0.04f), style = stroke)
        drawCircle(color, size.width * 0.17f, Offset(size.width * 0.5f, size.height * 0.56f), style = stroke)
    }
}

@Preview(showBackground = true)
@Composable
private fun CameraListPreview() { NcV380Theme { CameraList(CameraSettings(), {}, {}) } }
