package net.fix

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: NetworkViewModel by viewModels()
    private val networkManager: NetworkManager by lazy { NetworkManager(this) }

    // Launcher for requesting multiple permissions
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val deniedPermissions = permissions.filter { !it.value }.keys
        if (deniedPermissions.isNotEmpty()) {
            Snackbar.make(
                findViewById(android.R.id.content),
                "Some permissions were denied. Functionality may be limited. Please enable them in Settings.",
                Snackbar.LENGTH_LONG
            ).setAction("Settings") {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
            }.show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            NetworkScreen(viewModel, networkManager, ::showResetSettingsSnackbar)
        }
    }

    /**
     * Checks and requests required permissions.
     */
    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requiredPermissions.add(Manifest.permission.READ_PHONE_STATE)
        }

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    /**
     * Shows a Snackbar to guide the user to reset network settings.
     */
    private fun showResetSettingsSnackbar() {
        Snackbar.make(
            findViewById(android.R.id.content),
            "Please reset network settings manually.",
            Snackbar.LENGTH_LONG
        ).setAction("Settings") {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            startActivity(intent)
        }.show()
    }
}

@RequiresApi(Build.VERSION_CODES.N)
@Composable
fun NetworkScreen(
    viewModel: NetworkViewModel,
    networkManager: NetworkManager,
    onResetSettingsSuggested: () -> Unit
) {
    val state by viewModel.networkState.collectAsState()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var networkInfo by remember { mutableStateOf("") }
    val hasWifiPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                networkManager.context,
                Manifest.permission.ACCESS_WIFI_STATE
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    LaunchedEffect(hasWifiPermission) {
        if (hasWifiPermission) {
            scope.launch {
                while (true) {
                    networkInfo = networkManager.getNetworkInfo()
                    delay(5000) // Update every 5 seconds
                }
            }
        } else {
            networkInfo = "Wi-Fi info unavailable: Permission denied"
        }
    }

    LaunchedEffect(state) {
        if (state is NetworkState.Result) {
            val messages = (state as NetworkState.Result).messages
            if (messages.any { it.text.contains("Navigate to Settings") }) {
                onResetSettingsSuggested()
            }
            // Auto-scroll to bottom
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1A1A)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Network Info
            Text(
                text = networkInfo,
                color = Color(0xFF00FF00),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth(),
                textAlign = TextAlign.Start
            )

            // Output Text
            Text(
                text = when (state) {
                    is NetworkState.Loading -> "[*] Processing...".toAnnotatedString(MessageType.WARNING)
                    is NetworkState.Result -> (state as NetworkState.Result).messages
                        .fold(androidx.compose.ui.text.AnnotatedString.Builder()) { builder, msg ->
                            builder.append(msg.toAnnotatedString())
                            builder
                        }.toAnnotatedString()
                    is NetworkState.Error -> formatError((state as NetworkState.Error).message).toAnnotatedString(MessageType.ERROR)
                    is NetworkState.Idle -> "[ Network Fixer ]\nReady to reset your network...\n".toAnnotatedString(MessageType.SUCCESS)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color.White,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
            )

            // Progress Indicator
            if (state is NetworkState.Loading) {
                CircularProgressIndicator(
                    color = Color(0xFF00FF00),
                )
            }

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { viewModel.resetNetwork(isWifi = true) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF333333),
                        contentColor = Color(0xFF00FF00)
                    )
                ) {
                    Text("Wi-Fi")
                }
                Button(
                    onClick = { viewModel.diagnoseNetwork() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF333333),
                        contentColor = Color(0xFF00FF00)
                    )
                ) {
                    Text("Diag")
                }
                Button(
                    onClick = { viewModel.resetNetwork(isWifi = false) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF333333),
                        contentColor = Color(0xFF00FF00)
                    )
                ) {
                    Text("Mobile")
                }
                Button(
                    onClick = { networkManager.resetNetworkSettings() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF333333),
                        contentColor = Color(0xFF00FF00)
                    )
                ) {
                    Text("Reset")
                }
            }
        }
    }
}

fun String.toAnnotatedString(type: MessageType): androidx.compose.ui.text.AnnotatedString {
    return androidx.compose.ui.text.buildAnnotatedString {
        append(this@toAnnotatedString)
        addStyle(
            style = androidx.compose.ui.text.SpanStyle(color = Color(type.color)),
            start = 0,
            end = length
        )
    }
}