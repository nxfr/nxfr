package com.nxfr.android.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.nxfr.android.R
import com.nxfr.android.ui.icons.NxfrIcons

sealed class NxfrScreen(
    val route: String,
    val labelResId: Int,
    val icon: ImageVector,
) {
    data object Receive : NxfrScreen("receive", R.string.nav_receive, NxfrIcons.Receive)
    data object Send : NxfrScreen("send", R.string.nav_send, NxfrIcons.Send)
    data object Settings : NxfrScreen("settings", R.string.nav_settings, NxfrIcons.Settings)
    data object Transfer : NxfrScreen("transfer", 0, NxfrIcons.Send)
    data object WebUpload : NxfrScreen("web_upload", 0, NxfrIcons.Receive)
    data object WebShare : NxfrScreen("web_share", 0, NxfrIcons.WebLink)

    companion object {
        val bottomNavItems: List<NxfrScreen> get() = listOf(Receive, Send, Settings)
    }
}
