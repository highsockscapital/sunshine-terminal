package sunshine.terminal

import org.junit.Assert.*
import org.junit.Test

class FirstRunAutoBootTest {

    @Test fun bootsWhenPrereqsPresentAndNoToken() {
        val s = GuestStatus(
            imagePresent = true,
            kernelPresent = true,
            crosvm = "/apex/com.android.virt/bin/vm",
            sshPresent = true,
            hasToken = false,
            missing = emptyList(),
        )
        assertTrue(shouldAutoBoot(s))
    }

    @Test fun skipsWhenAlreadyBooted() {
        val s = GuestStatus(
            imagePresent = true,
            kernelPresent = true,
            crosvm = "crosvm",
            sshPresent = true,
            hasToken = true,
            sshPort = 2222,
            missing = emptyList(),
        )
        assertFalse(shouldAutoBoot(s))
    }

    @Test fun skipsWhenPrereqsMissing() {
        val s = GuestStatus(missing = listOf("guest image (/x/debian.img)", "ssh client (Termux openssh)"))
        assertFalse(shouldAutoBoot(s))
    }
}
