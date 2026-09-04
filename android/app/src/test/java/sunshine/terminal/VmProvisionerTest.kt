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
        // ensureProvisionedDirs uses production floors (50MB): the tiny
        // fixture is rejected as truncated — but must fail cleanly.
        assertTrue(res.missingOptional.any { it.startsWith("debian.img") })
        assertFalse(File(vm, "debian.img").exists())
        assertEquals("kernel-bytes", File(vm, "Image").readText())
        assertTrue(res.unpacked.contains("Image"))
    }

    @Test fun gzExpandAndSparseExtend() {
        val vm = tmpRoot()
        val raw = "debian-rootfs-bytes".toByteArray()
        val gzBytes = run {
            val bos = java.io.ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(raw) }
            bos.toByteArray()
        }
        var lastProgress = 0L
        val r = VmProvisioner.provisionRootfs(
            openAsset = { name ->
                if (name == "guest/debian.img.gz") ByteArrayInputStream(gzBytes) else null
            },
            vmDir = vm,
            onProgress = { lastProgress = it },
            minValidBytes = 1L,
            virtualSizeBytes = 1024L,
        )
        assertTrue(r is VmProvisioner.RootfsResult.Unpacked)
        val expanded = File(vm, "debian.img")
        assertEquals(1024L, expanded.length())
        expanded.inputStream().use { inp ->
            val head = ByteArray(raw.size)
            var off = 0
            while (off < head.size) {
                val n = inp.read(head, off, head.size - off)
                if (n < 0) break
                off += n
            }
            assertEquals(raw.size, off)
            assertArrayEquals(raw, head)
            // Sparse tail reads as zeros without consuming flash.
            assertEquals(0, inp.read())
        }
        assertEquals(raw.size.toLong(), lastProgress)
        // Second call is a no-op (already valid).
        val r2 = VmProvisioner.provisionRootfs(
            openAsset = { null },
            vmDir = vm,
            minValidBytes = 1L,
        )
        assertTrue(r2 is VmProvisioner.RootfsResult.Ready)
    }

    @Test fun xzPreferredOverGz() {
        val vm = tmpRoot()
        val gzBytes = run {
            val bos = java.io.ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write("gz-payload".toByteArray()) }
            bos.toByteArray()
        }
        val xzBytes = run {
            val bos = java.io.ByteArrayOutputStream()
            org.tukaani.xz.XZOutputStream(bos, org.tukaani.xz.LZMA2Options()).use {
                it.write("xz-payload".toByteArray())
            }
            bos.toByteArray()
        }
        val r = VmProvisioner.provisionRootfs(
            openAsset = { name ->
                when (name) {
                    "guest/debian.img.xz" -> ByteArrayInputStream(xzBytes)
                    "guest/debian.img.gz" -> ByteArrayInputStream(gzBytes)
                    else -> null
                }
            },
            vmDir = vm,
            minValidBytes = 1L,
            virtualSizeBytes = 64L,
        )
        assertTrue(r is VmProvisioner.RootfsResult.Unpacked)
        File(vm, "debian.img").inputStream().use { inp ->
            val head = ByteArray("xz-payload".length)
            assertEquals(head.size, inp.read(head))
            assertEquals("xz-payload", String(head))
        }
    }

    @Test fun partialRootfsIsReplaced() {
        val vm = tmpRoot().also { it.mkdirs() }
        // Stale file (13B) below the floor, fresh payload (17B) above it.
        File(vm, "debian.img").writeText("stale-partial")
        val gzBytes = run {
            val bos = java.io.ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write("fresh-image-bytes".toByteArray()) }
            bos.toByteArray()
        }
        val r = VmProvisioner.provisionRootfs(
            openAsset = { name ->
                if (name == "guest/debian.img.gz") ByteArrayInputStream(gzBytes) else null
            },
            vmDir = vm,
            minValidBytes = 15L,
            virtualSizeBytes = 64L,
        )
        assertTrue(r is VmProvisioner.RootfsResult.Unpacked)
        File(vm, "debian.img").inputStream().use { inp ->
            val head = ByteArray("fresh-image-bytes".length)
            assertEquals(head.size, inp.read(head))
            assertEquals("fresh-image-bytes", String(head))
        }
    }

    @Test fun sparseExtendNeverShrinks() {
        val f = File(tmpRoot(), "img").also { it.parentFile.mkdirs() }
        f.writeBytes(ByteArray(100))
        assertEquals(1024L, VmProvisioner.sparseExtend(f, 1024L))
        assertEquals(1024L, VmProvisioner.sparseExtend(f, 64L))
        assertEquals(1024L, f.length())
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
