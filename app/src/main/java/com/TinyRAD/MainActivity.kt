package com.TinyRAD

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.TinyRAD.data.models.UsbConnectionState
import com.TinyRAD.data.repository.AppLog
import com.TinyRAD.data.repository.LogLevel
import com.TinyRAD.ui.Screen
import com.TinyRAD.ui.screens.*
import com.TinyRAD.ui.theme.*
import com.TinyRAD.viewmodel.TinyRadViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: TinyRadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle     = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        // Keep screen on while this app is in the foreground
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppLog.init(this)
        AppLog.info("TinyRadApp started")
        handleUsbIntent(intent)
        setContent {
            TinyRadAppTheme {
                TinyRadApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: UsbDevice? =
                IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            device?.let {
                AppLog.info("USB device attached via intent: ${it.productName}")
                viewModel.findAndConnect()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.usbManager.cleanup()
        AppLog.close()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TinyRadApp(viewModel: TinyRadViewModel) {
    val navController  = rememberNavController()
    val currentEntry   by navController.currentBackStackEntryAsState()
    val currentRoute   = currentEntry?.destination?.route

    val uiState     by viewModel.uiState.collectAsState()
    val hideLogTab  by viewModel.hideLogTab.collectAsState()
    val isConnected  = uiState.connectionState == UsbConnectionState.CONNECTED
    val isStreaming  = uiState.isStreaming

    val context      = LocalContext.current
    var showExitDialog by remember { mutableStateOf(false) }

    // If the user hides the Log tab while the Log screen is open, leave it.
    LaunchedEffect(hideLogTab, currentRoute) {
        if (hideLogTab && currentRoute == Screen.Log.route) {
            navController.navigate(Screen.Home.route) {
                popUpTo(0); launchSingleTop = true
            }
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor   = RadarDarkMid,
            title = { Text("Exit TinyRAD?", color = RadarOnSurface) },
            text  = {
                Text(
                    if (isStreaming)
                        "Streaming is active. Exiting will stop the radar and close the USB connection."
                    else
                        "This will close the USB connection and quit the application.",
                    color = RadarOnSurface.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    viewModel.shutdown()
                    (context as? Activity)?.finishAndRemoveTask()
                }) { Text("Exit", color = RadarError) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Cancel", color = RadarOnSurface.copy(alpha = 0.7f))
                }
            }
        )
    }

    val bottomRoutes = listOf(
        Screen.Home.route, Screen.Radar.route,
        Screen.Recordings.route, Screen.Log.route, Screen.Settings.route
    )
    val showBottom = currentRoute in bottomRoutes

    Scaffold(
        bottomBar = {
            if (showBottom) {
                NavigationBar(containerColor = RadarDarkMid) {
                    NavItem(
                        selected = currentRoute == Screen.Home.route,
                        icon     = Icons.Default.Home,
                        label    = "Home",
                        onClick  = {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(0)
                                launchSingleTop = true
                            }
                        }
                    )
                    NavItem(
                        selected = currentRoute == Screen.Radar.route,
                        icon     = Icons.Default.TrackChanges,   // radar-style icon
                        label    = "Radar",
                        enabled  = isConnected && isStreaming,
                        onClick  = {
                            navController.navigate(Screen.Radar.route) {
                                launchSingleTop = true
                            }
                        }
                    )
                    NavItem(
                        selected = currentRoute == Screen.Recordings.route,
                        icon     = Icons.Default.TableChart,
                        label    = "Files",
                        onClick  = {
                            navController.navigate(Screen.Recordings.route) {
                                launchSingleTop = true
                            }
                        }
                    )
                    if (!hideLogTab) {
                        val logEntries  by AppLog.entries.collectAsState()
                        val errorCount  = remember(logEntries) {
                            logEntries.count { it.level == LogLevel.ERROR }
                        }
                        NavItemBadged(
                            selected    = currentRoute == Screen.Log.route,
                            icon        = Icons.Default.Terminal,
                            label       = "Log",
                            badgeCount  = errorCount,
                            onClick     = {
                                navController.navigate(Screen.Log.route) {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                    NavItem(
                        selected = currentRoute == Screen.Settings.route,
                        icon     = Icons.Default.Settings,
                        label    = "Settings",
                        onClick  = {
                            navController.navigate(Screen.Settings.route) {
                                launchSingleTop = true
                            }
                        }
                    )
                    // Exit — never "selected"; opens a confirmation dialog
                    NavItem(
                        selected = false,
                        icon     = Icons.AutoMirrored.Filled.ExitToApp,
                        label    = "Exit",
                        onClick  = { showExitDialog = true }
                    )
                }
            }
        }
    ) { pad ->
        NavHost(
            navController    = navController,
            startDestination = Screen.Home.route,
            modifier         = Modifier.padding(pad).fillMaxSize()
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel         = viewModel,
                    onNavigateToRadar = {
                        navController.navigate(Screen.Radar.route) { launchSingleTop = true }
                    },
                    onNavigateToConnect = {
                        navController.navigate(Screen.Connect.route)
                    },
                    onNavigateToAbout = {
                        navController.navigate(Screen.About.route)
                    }
                )
            }
            composable(Screen.Connect.route) {
                ConnectScreen(
                    viewModel = viewModel,
                    onBack    = { navController.popBackStack() }
                )
            }
            composable(Screen.Radar.route) {
                RadarScreen(
                    viewModel    = viewModel,
                    onDisconnect = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(0)
                        }
                    }
                )
            }
            composable(Screen.Recordings.route) {
                RecordingsScreen(
                    viewModel   = viewModel,
                    onBack      = { navController.popBackStack() },
                    onViewFile  = { path ->
                        navController.navigate(Screen.CsvViewer.route(path))
                    }
                )
            }
            composable(
                route     = Screen.CsvViewer.route,
                arguments = listOf(androidx.navigation.navArgument("path") {
                    type = androidx.navigation.NavType.StringType
                })
            ) { back ->
                val encodedPath = back.arguments?.getString("path") ?: ""
                val path = java.net.URLDecoder.decode(encodedPath, "UTF-8")
                CsvViewerScreen(filePath = path, onBack = { navController.popBackStack() })
            }
            composable(Screen.Log.route) {
                LogScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack    = { navController.popBackStack() }
                )
            }
            composable(Screen.About.route) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

// ── Nav bar helpers ───────────────────────────────────────────────────────────

@Composable
private fun RowScope.NavItem(
    selected: Boolean,
    icon:     androidx.compose.ui.graphics.vector.ImageVector,
    label:    String,
    enabled:  Boolean = true,
    onClick:  () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        enabled  = enabled,
        onClick  = onClick,
        icon     = { Icon(icon, null) },
        label    = { Text(label) },
        colors   = NavigationBarItemDefaults.colors(
            selectedIconColor   = RadarAccent,
            selectedTextColor   = RadarAccent,
            indicatorColor      = RadarAccent.copy(alpha = 0.15f),
            unselectedIconColor = RadarOnSurface.copy(alpha = 0.4f),
            unselectedTextColor = RadarOnSurface.copy(alpha = 0.4f)
        )
    )
}

@Composable
private fun RowScope.NavItemBadged(
    selected:   Boolean,
    icon:       androidx.compose.ui.graphics.vector.ImageVector,
    label:      String,
    badgeCount: Int,
    onClick:    () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick  = onClick,
        icon     = {
            BadgedBox(badge = {
                if (badgeCount > 0) Badge(containerColor = RadarError) {
                    Text(if (badgeCount > 9) "9+" else badgeCount.toString())
                }
            }) { Icon(icon, null) }
        },
        label    = { Text(label) },
        colors   = NavigationBarItemDefaults.colors(
            selectedIconColor   = RadarAccent,
            selectedTextColor   = RadarAccent,
            indicatorColor      = RadarAccent.copy(alpha = 0.15f),
            unselectedIconColor = RadarOnSurface.copy(alpha = 0.4f),
            unselectedTextColor = RadarOnSurface.copy(alpha = 0.4f)
        )
    )
}
