package com.rfsat.tinyrad

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.*
import com.rfsat.tinyrad.data.models.UsbConnectionState
import com.rfsat.tinyrad.data.repository.AppLog
import com.rfsat.tinyrad.data.repository.LogLevel
import com.rfsat.tinyrad.ui.Screen
import com.rfsat.tinyrad.ui.screens.*
import com.rfsat.tinyrad.ui.theme.*
import com.rfsat.tinyrad.viewmodel.TinyRadViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: TinyRadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        AppLog.init(this)          // start file logging before anything else
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
    val isConnected  = uiState.connectionState == UsbConnectionState.CONNECTED
    val isStreaming  = uiState.isStreaming

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
                    viewModel = viewModel,
                    onBack    = { navController.popBackStack() }
                )
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
