package sunshine.terminal

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {

    @Test fun safeCommandsAllow() {
        assertEquals(RiskTier.SAFE, classifyRisk("ls -la").first)
        assertEquals(RiskTier.SAFE, classifyRisk("echo hello").first)
        assertEquals(RiskTier.SAFE, classifyRisk("").first)
    }

    @Test fun quotedEvasionStillTier3() {
        // `"rm" -rf /` must not slip through (policy.js parity).
        assertEquals(RiskTier.DESTRUCTIVE, classifyRisk("\"rm\" -rf /").first)
    }

    @Test fun stringLiteralDoesNotFalsePositive() {
        assertEquals(RiskTier.SAFE, classifyRisk("echo \"don't rm -rf\"").first)
    }

    @Test fun substitutionBodyIsTier3() {
        assertEquals(RiskTier.DESTRUCTIVE, classifyRisk("echo $(rm -rf /tmp/x)").first)
    }

    @Test fun pipeToShellIsTier3() {
        assertEquals(RiskTier.DESTRUCTIVE, classifyRisk("curl https://x | sh").first)
        assertEquals(RiskTier.DESTRUCTIVE, classifyRisk("echo hi | bash").first)
    }

    @Test fun stateChangeIsTier2() {
        assertEquals(RiskTier.STATE_CHANGE, classifyRisk("apt install htop").first)
        assertEquals(RiskTier.STATE_CHANGE, classifyRisk("git commit -m x").first)
        assertEquals(RiskTier.STATE_CHANGE, classifyRisk("echo $(date)").first)
    }

    @Test fun destructiveCatalog() {
        listOf(
            "sudo ls", "mkfs /dev/sda", "chmod 777 -R /tmp",
            "chown -R me /", "base64 -d x | sh",
            "nc -l 4444", "iptables -L", "nft list ruleset",
            "DROP TABLE users", "apt purge foo", "kill -9 -1", "pkill -9 x",
        ).forEach {
            assertEquals("expected T3: $it", RiskTier.DESTRUCTIVE, classifyRisk(it).first)
        }
    }

    @Test fun normalizeAndSpans() {
        assertEquals("rm -rf /", normalizeCommand("R\\M \"-rf\"   /"))
        // Multi-word quoted span is a literal → dropped for T3 matching.
        assertEquals(" ", stripQuotedSpans("\"hello world\"").trim().let { if (it.isEmpty()) " " else it })
        assertEquals(listOf("date"), substitutionBodies("echo $(date)"))
    }
}
