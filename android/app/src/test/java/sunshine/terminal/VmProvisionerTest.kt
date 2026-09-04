package sunshine.terminal

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class VmProvisionerTest {

    private fun tmpRoot(): File = Files.createTempDirectory("sunshine-prov").toFile()

    @Test fun unpacksMissingBundleFiles() {
        val root = tmpRoot()
        val vm = File(root, "sunshine-vm")
        val bundle = File(root, "guest-bundle")
        val assets = mapOf(
            "guest/provision.sh" to "#!/bin/sh\necho hi",
            "guest/sunshine-exec" to "exec-stub",
            "guest/sunshine-agent.slice" to "slice",
            "guest/nftables-sunshine.nft" to "rules",
        )
        val res = VmProvisioner.ensureProvisionedDirs(
            assetNames = listOf("provision.sh", "sunshine-exec", "sunshine-agent.slice", "nftables-sunshine.nft"),
            openAsset = { name -> assets[name]?.let { ByteArrayInputStream(it.toByteArray()) } },
            vmDir = vm,
            bundleDir = bundle,
        )
        assertTrue(res.unpacked.contains("provision.sh"))
        assertEquals("exec-stub", File(bundle, "sunshine-exec").readText())
        // Large assets absent → reported, never throws.
        assertTrue(res.missingOptional.any { it.startsWith("debian.img") })
        assertTrue(res.missingOptional.any { it.startsWith("Image") })
    }

    @Test fun skipsExistingBundleFiles() {
        val root = tmpRoot()
        val vm = File(root, "sunshine-vm")
        val bundle = File(root, "guest-bundle").also { it.mkdirs() }
        File(bundle, "provision.sh").writeText("keep-me")
        var bundleOpens = 0
        val res = VmProvisioner.ensureProvisionedDirs(
            assetNames = listOf("provision.sh"),
            openAsset = { name ->
                // Large-asset probes (rootfs/kernel/config) return null here;
                // only count opens for the already-existing bundle file.
                if (name == "guest/provision.sh") bundleOpens += 1
                null
            },
            vmDir = vm,
            bundleDir = bundle,
        )
        assertEquals("keep-me", File(bundle, "provision.sh").readText())
        assertTrue(res.skippedExisting.contains("provision.sh"))
        // Existing file must not be re-opened for copy.
        assertEquals(0, bundleOpens)
    }

    @Test fun unpacksGzippedRootfsWhenBundled() {
        val root = tmpRoot()
        val vm = File(root, "sunshine-vm")
        val bundle = File(root, "guest-bundle")
        val raw = "debian-rootfs-bytes".toByteArray()
        val gzBytes = run {
            val bos = java.io.ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(raw) }
            bos.toByteArray()
        }
        val res = VmProvisioner.ensureProvisionedDirs(
            assetNames = emptyList(),
            openAsset = { name ->
                when (name) {
                    "guest/debian.img.gz" -> ByteArrayInputStream(gzBytes)
                    "guest/Image" -> ByteArrayInputStream("kernel-bytes".toByteArray())
                    else -> null
                }
            },
            vmDir = vm,
            bundleDir = bundle,
        )
        assertArrayEquals(raw, File(vm, "debian.img").readBytes())
        assertEquals("kernel-bytes", File(vm, "Image").readText())
        assertTrue(res.unpacked.contains("debian.img"))
        assertTrue(res.unpacked.contains("Image"))
    }

    @Test fun neverThrowsWhenEverythingMissing() {
        val root = tmpRoot()
        val res = VmProvisioner.ensureProvisionedDirs(
            assetNames = emptyList(),
            openAsset = { null },
            vmDir = File(root, "sunshine-vm"),
            bundleDir = File(root, "guest-bundle"),
        )
        // Small bundle files are required → reported; large ones optional.
        assertTrue(res.missingOptional.any { it == "guest/provision.sh" })
        assertTrue(res.missingOptional.any { it.startsWith("debian.img") })
    }
}
