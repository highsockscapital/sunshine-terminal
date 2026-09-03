package sunshine.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import sunshine.terminal.FakeGuestChannel
import sunshine.terminal.TerminalScreen
import sunshine.terminal.TerminalViewModel
import sunshine.design.sunshineColorScheme
import sunshine.design.sunshineTypography

class MainActivity : ComponentActivity() {

    // TODO: replace FakeGuestChannel with the VSOCK multiplexer (GuestChannel).
    private val terminalViewModel: TerminalViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TerminalViewModel(FakeGuestChannel()) as T
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
