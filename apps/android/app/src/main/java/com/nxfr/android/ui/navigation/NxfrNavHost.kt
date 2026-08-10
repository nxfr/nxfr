package com.nxfr.android.ui.navigation


import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nxfr.android.ui.screens.ReceiveScreen
import com.nxfr.android.ui.screens.SendScreen
import com.nxfr.android.ui.screens.SettingsScreen
import com.nxfr.android.ui.screens.TransferScreen

@Composable
fun NxfrNavHost(
    deviceName: String,
    deviceId: String,
    onDeviceNameChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                NxfrScreen.bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = stringResource(screen.labelResId)
                            )
                        },
                        label = { Text(stringResource(screen.labelResId)) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NxfrScreen.Receive.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(tween(200)) },
            exitTransition = { fadeOut(tween(200)) },
        ) {
            composable(NxfrScreen.Receive.route) {
                ReceiveScreen(
                    deviceName = deviceName,
                    deviceId = deviceId,
                    onDeviceNameChanged = onDeviceNameChanged,
                )
            }
            composable(NxfrScreen.Send.route) {
                SendScreen()
            }
            composable(NxfrScreen.Settings.route) {
                SettingsScreen(
                    deviceName = deviceName,
                    deviceId = deviceId,
                    onDeviceNameChanged = onDeviceNameChanged,
                )
            }
            composable(NxfrScreen.Transfer.route) {
                TransferScreen()
            }
        }
    }
}
