package sunshine.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import sunshine.design.sunshineColorScheme
import sunshine.design.sunshineTypography

class MainActivity : ComponentActivity() {

    // Production path: framed channel into the live Debian pVM
    // (VsockFrameMultiplexer → vsock agent, SSH bootstrap fallback).
    // FakeGuestChannel lives only in TerminalScreen.kt for @Previews —
    // it must never back this screen (it just echoes "(preview) ran: …").
    private val terminalViewModel: TerminalViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                // Silent first-run setup: VmProvisioner unpacks assets/guest
                // (idempotent, best-effort). SunshineApp already kicked this
                // off in the background; re-run here so the bundle is ready
                // even on a cold process that skipped Application init in
                // tests/previews.
                val appCtx = applicationContext
                try {
                    VmProvisioner.ensureProvisioned(appCtx)
                } catch (_: Exception) {
                }
                val vmDir = VmProvisioner.vmDir(appCtx).also { it.mkdirs() }
                val bundleDir = VmProvisioner.bundleDir(appCtx).also { it.mkdirs() }
                // stateDir backs the persisted agent-step counter, JSONL
                // audit log, and ssh known_hosts pinning.
                // Transport preference: vsock (no ssh/IP stack) → SSH
                // (bootstrap fallback) — see VsockSocketTransport.
                val vsock = VsockSocketTransport()
                val ssh = SshGuestTransport(
                    vmDir, bundleDir = bundleDir, vsockProbe = vsock::probe,
                )
                val vmChannel = VsockGuestChannel(
                    HybridGuestTransport(vsock, vsock::probe, ssh),
                    stateDir = vmDir,
                )
                // On-device shell: zero-setup fallback (no ssh/crosvm/Termux).
                // The ViewModel starts here and auto-upgrades to vmChannel
                // once the pVM is booted + provisioned.
                val localChannel = LocalShellChannel(rootDir = appCtx.filesDir)
                return TerminalViewModel(channel = localChannel, vmChannel = vmChannel) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = sunshineColorScheme(),
                typography = sunshineTypography(),
            ) {
                TerminalScreen(viewModel = terminalViewModel)
            }
        }
    }
}
