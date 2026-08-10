package com.nxfr.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.nxfr.android.R

sealed class NxfrScreen(
    val route: String,
    val labelResId: Int,
    val icon: ImageVector,
) {
    data object Receive : NxfrScreen("receive", R.string.nav_receive, Icons.AutoMirrored.Outlined.CallReceived)
    data object Send : NxfrScreen("send", R.string.nav_send, Icons.AutoMirrored.Outlined.Send)
    data object Settings : NxfrScreen("settings", R.string.nav_settings, Icons.Outlined.Settings)
    data object Transfer : NxfrScreen("transfer", 0, Icons.AutoMirrored.Outlined.Send)

    companion object {
        val bottomNavItems = listOf(Receive, Send, Settings)
    }
}
