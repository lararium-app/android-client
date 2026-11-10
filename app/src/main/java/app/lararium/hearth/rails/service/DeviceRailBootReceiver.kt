package app.lararium.hearth.rails.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.lararium.service.AssistantService

/**
 * Boot receiver gating DeviceRailService startup on device boot (F7, spec A2).
 * Only starts the service when the device is already paired.
 */
class DeviceRailBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, AssistantService::class.java).apply { action = AssistantService.ACTION_ACTIVATE })
            val store = DevicePairingStore(context)
            if (store.isPaired()) {
                DeviceRailService.ensureRunning(context)
            }
        }
    }
}
