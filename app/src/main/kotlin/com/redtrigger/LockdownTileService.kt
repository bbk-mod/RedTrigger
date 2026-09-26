package com.redtrigger

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlin.concurrent.thread

/**
 * Quick Settings tile that fires the same Lockdown as the trigger action: lock the
 * device and disable biometric/trust-agent unlock until the primary credential is
 * entered.
 *
 * The command runs as the shell uid through the Shizuku UserService, so the tile is
 * only live while that service is up (i.e. triggers are enabled). When it is not, the
 * tile shows as unavailable rather than silently doing nothing.
 */
class LockdownTileService : TileService() {

    override fun onStartListening() {
        sync()
    }

    override fun onClick() {
        if (!InputReader.isRunning) {
            sync()
            return
        }

        thread(name = "lockdown-tile", isDaemon = true) {
            ActionDispatcher.runLockdown()
        }
    }

    private fun sync() {
        qsTile?.apply {
            label = "Lockdown"
            state = if (InputReader.isRunning) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            updateTile()
        }
    }
}
