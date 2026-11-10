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
            val store = DevicePairingStore(context)
            if (store.isPaired()) {
                // Re-arm the assistant shield after reboot for paired devices only;
                // unpaired installs must not surface a persistent notification.
                context.startForegroundService(
                    Intent(context, AssistantService::class.java).apply {
                        action = AssistantService.ACTION_ACTIVATE
                    }
                )
                DeviceRailService.ensureRunning(context)
            }
        }
    }
}