package app.lararium.service

import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Foreground-service type bitmasks. androidx.core 1.17 / compileSdk 36 expose
 * ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE (API 34) directly.
 *
 * Before API 34 specialUse does not exist, so we defer to the manifest-declared
 * types (FOREGROUND_SERVICE_TYPE_MANIFEST) rather than pass an unknown bit.
 */
object FgsTypes {
    /** Rails stream service (#91): specialUse only. */
    fun specialUse(sdk: Int = Build.VERSION.SDK_INT): Int =
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        }

    /**
     * Assistant service (#93): specialUse | microphone. The microphone bit is
     * only added when RECORD_AUDIO is granted — on API 34+ the platform throws
     * SecurityException when a microphone FGS starts without it.
     */
    fun assistant(micGranted: Boolean, sdk: Int = Build.VERSION.SDK_INT): Int {
        if (sdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (micGranted) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return type
    }
}
