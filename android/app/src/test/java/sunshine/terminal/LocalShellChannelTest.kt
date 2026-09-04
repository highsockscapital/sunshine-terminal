package sunshine.terminal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalShellChannelTest {

    private fun tmpRoot(): File = Files.createTempDirectory("sunshine-local").toFile()

    @Test fun safeCommandRunsLocally() = runBlocking {
        val ch = LocalShellChannel(tmpRoot())
        val out = ch.exec("echo hello", "human", approved = false, blockId = 1L)
        assertTrue(out is ChannelOutcome.Completed)
        out as ChannelOutcome.Completed
        assertEquals(0, out.exitCode)
        assertEquals(RiskTier.SAFE, out.tier)
        assertTrue(out.lines.any { it.contains("hello") })
    }

    @Test fun destructiveNeedsApproval() = runBlocking {
        val ch = LocalShellChannel(tmpRoot())
        val out = ch.exec("sudo ls", "human", approved = false, blockId = 2L)
        assertTrue(out is ChannelOutcome.NeedsApproval)
        out as ChannelOutcome.NeedsApproval
        assertEquals(RiskTier.DESTRUCTIVE, out.request.tier)
        assertTrue(out.request.explicit)
    }

    @Test fun failingCommandReportsExitCode() = runBlocking {
        val ch = LocalShellChannel(tmpRoot())
        val out = ch.exec("exit 3", "human", approved = false, blockId = 3L)
        assertTrue(out is ChannelOutcome.Completed)
        assertEquals(3, (out as ChannelOutcome.Completed).exitCode)
    }

    @Test fun workspaceRoundTrip() = runBlocking {
        val root = tmpRoot()
        File(root, "sub").mkdirs()
        File(root, "notes.md").writeText("# Hi\n\nbody\n")
        val ch = LocalShellChannel(root)
        val listing = ch.listWorkspace(root.absolutePath)
        assertNull(listing.error)
        assertTrue(listing.entries.any { it.name == "sub" && it.isDirectory })
        assertTrue(listing.entries.any { it.name == "notes.md" && !it.isDirectory })
        val content = ch.readWorkspaceFile(File(root, "notes.md").absolutePath)
        assertNull(content.error)
        assertTrue(content.isMarkdown)
        assertTrue(content.lines.any { it.contains("Hi") })
    }

    @Test fun guestStatusIsHonest() = runBlocking {
        val ch = LocalShellChannel(tmpRoot())
        val status = ch.guestStatus()
        assertTrue(status.missing.isNotEmpty())
    }
}
