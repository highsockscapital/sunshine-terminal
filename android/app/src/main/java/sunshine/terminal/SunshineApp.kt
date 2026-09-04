// SunshineApp — Application entry point for silent first-run provisioning.
// Kicks VmProvisioner.ensureProvisioned() on a background thread so the
// Debian rootfs/kernel + guest bundle are in place before (or while) the
// user first sees the terminal canvas. Best-effort: never crashes launch.
package sunshine.terminal

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SunshineApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            try {
                VmProvisioner.ensureProvisioned(applicationContext)
            } catch (_: Exception) {
                // Never crash startup for provisioning.
            }
        }
    }

    override fun onTerminate() {
        try {
            appScope.cancel()
        } catch (_: Exception) {
        }
        super.onTerminate()
    }
}
