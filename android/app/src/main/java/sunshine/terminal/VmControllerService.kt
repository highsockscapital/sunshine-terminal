// Sunshine :terminal-core — pVM controller ForegroundService anchor (SPEC).
// Lives in the app module (package sunshine.terminal). The CLI-side
// equivalent is `scli vm watch` (src/runtimes/heartbeat.js): same 2.5s
// cadence, same 2-miss budget, same recoverGuest() path.
//
// Why this exists: Android's Low Memory Killer silently reaps background
// host processes under RAM pressure. If :terminal-core dies mid-VSOCK
// write, the guest is orphaned with a live session token nobody owns.
// Binding the controller thread to a ForegroundService keeps the host
// side in the foreground bucket (last to kill) and gives the user a
// visible, stoppable handle on the VM session.
//
// Manifest (app/src/main/AndroidManifest.xml):
//   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
//   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
//   <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
//   <service
//       android:name=".VmControllerService"
//       android:foregroundServiceType="connectedDevice"
//       android:exported="false" />
//
// Notes:
// - FOREGROUND_SERVICE_CONNECTED_DEVICE requires API 34+ (minSdk 34 ✓).
//   The service must call startForeground() within the ANR window after
//   startForegroundService(), with a persistent notification showing the
//   guest state (mirrors the CLI Power Saver chip copy).
// - Heartbeat transport is injected (VsockChannel now, ssh-exec today):
//   the miss budget and recovery policy below never name a transport.
// - Doze/App Standby still defer timers; the service holds no wakelock by
//   default — heartbeat drift under Doze is reported as misses, which is
//   the honest signal (recovery is idempotent).
package sunshine.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Transport-agnostic liveness probe. Returns true on ack. */
fun interface GuestPing {
    suspend fun ping(): Boolean
}

/** Recovery hook: fence token + stale state, optionally reboot. Mirrors recoverGuest(). */
fun interface GuestRecovery {
    suspend fun recover(autoBoot: Boolean): Boolean
}

class VmControllerService : Service() {

    companion object {
        const val CHANNEL_ID = "sunshine_vm_controller"
        const val NOTIFICATION_ID = 4201
        const val HEARTBEAT_INTERVAL_MS = 2_500L
        const val MAX_MISSES = 2
        const val EXTRA_AUTO_BOOT = "sunshine.AUTO_BOOT"
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var watchJob: Job? = null

    // Injected by the :terminal-core graph (Hilt/manual).
    var ping: GuestPing? = null
    var recovery: GuestRecovery? = null
    var autoBoot: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        autoBoot = intent?.getBooleanExtra(EXTRA_AUTO_BOOT, false) == true
        val notification = buildNotification("Sunshine VM attached — heartbeat armed")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        startWatch()
        // If LMK still takes us, restart the watch (state re-fences on boot).
        return START_STICKY
    }

    private fun startWatch() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            var misses = 0
            var recovered = false
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val alive = try {
                    ping?.ping() == true
                } catch (_: Exception) {
                    false
                }
                if (alive) {
                    misses = 0
                    recovered = false
                    updateNotification("Sunshine VM attached — guest alive")
                } else {
                    misses += 1
                    updateNotification("Sunshine VM — heartbeat missed ($misses/$MAX_MISSES)")
                    if (misses >= MAX_MISSES && !recovered) {
                        recovered = true
                        val ok = try {
                            recovery?.recover(autoBoot) == true
                        } catch (_: Exception) {
                            false
                        }
                        updateNotification(
                            if (ok) "Sunshine VM recovered cleanly"
                            else "Sunshine VM lost — open app to recover",
                        )
                    }
                }
            }
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Sunshine VM controller",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keeps the Linux guest session alive and watched"
                },
            )
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sunshine")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not orphan the guest: stop the watch so
        // the next start re-fences instead of double-driving recovery.
        watchJob?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
