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
import java.io.File

class MainActivity : ComponentActivity() {

    // Production path: framed channel into the live Debian pVM
    // (VsockFrameMultiplexer → SshGuestTransport → sunshine-exec).
    // FakeGuestChannel lives only in TerminalScreen.kt for @Previews —
    // it must never back this screen (it just echoes "(preview) ran: …").
    private val terminalViewModel: TerminalViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val vmDir = File(filesDir, "sunshine-vm").also { it.mkdirs() }
                // stateDir backs the persisted agent-step counter, JSONL
                // audit log, and ssh known_hosts pinning.
                val channel = VsockGuestChannel(SshGuestTransport(vmDir), stateDir = vmDir)
                return TerminalViewModel(channel) as T
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
