package com.nxfr.android.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class NxfrTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val listening = NxfrService.isListening.value
        if (listening) {
            NxfrService.stopListening(this)
        } else {
            NxfrService.startListening(this)
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val listening = NxfrService.isListening.value
        tile.state = if (listening) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (listening) "NXFR Visible" else "NXFR Hidden"
        tile.updateTile()
    }
}
