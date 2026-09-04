// Sunshine VmProvisioner — silent first-run setup for the Debian pVM.
// Runs on app startup (SunshineApp) + MainActivity ViewModel creation so the
// user never has to tap Boot/Provision manually on first launch.
//
// What it does (best-effort, never throws):
//   1. Ensures filesDir/sunshine-vm + filesDir/guest-bundle exist.
//   2. Unpacks the small guest bundle (assets/guest/* → guest-bundle/).
//   3. Expands the optional compressed rootfs if the packager bundled one:
//        assets/guest/debian.img.xz → sunshine-vm/debian.img (xz, preferred)
//        assets/guest/debian.img.gz → sunshine-vm/debian.img (gzip fallback)
//      then sparse-extends it to the 2G virtual size. ext4/f2fs allocate
//      blocks only for dirty regions, so a ~600MB rootfs reports 2G to the
//      guest while consuming ~600MB of flash. Zero-filled images compress to
//      ~200-300MB, which is what makes bundling/downloading feasible.
//      !! truncate alone is NOT a rootfs: the artifact must be a real ext4
//      image (debootstrap/Debian-cloud, free space zeroed before packing).
//   4. Unpacks the optional kernel + config overlay (byte-identical, AVF).
//      Large artifacts are NOT in the repo — when absent they are reported
//      in ProvisionResult.missingOptional so the UI shows a calm hint.
//      A build may bundle them or fetch them via a downloader into the same
//      files; this code accepts both without changes.
//
// The pure-File core (ensureProvisionedDirs + provisionRootfs) is
// unit-tested without Android.
package sunshine.terminal

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

data class ProvisionResult(
    val unpacked: List<String> = emptyList(),
    val skippedExisting: List<String> = emptyList(),
    val missingOptional: List<String> = emptyList(),
)

object VmProvisioner {
    const val VM_DIR_NAME = "sunshine-vm"
    const val BUNDLE_DIR_NAME = "guest-bundle"

    /** Small text bundle — always shipped in assets/guest/. */
    val BUNDLE_FILES = listOf(
        "provision.sh",
        "sunshine-exec",
        "sunshine-vsock-agent.py",
        "sunshine-vsock-agent.service",
        "sunshine-agent.slice",
        "nftables-sunshine.nft",
    )

    const val ROOTFS_ASSET_XZ = "guest/debian.img.xz"
    const val ROOTFS_ASSET_GZ = "guest/debian.img.gz"
    const val ROOTFS_FILE = "debian.img"
    /** Virtual capacity reported to the guest (sparse — flash only pays for dirty blocks). */
    const val ROOTFS_VIRTUAL_SIZE_BYTES = 2L * 1024 * 1024 * 1024
    /** Sanity floor: a real minimal Debian rootfs is hundreds of MB. Below
     *  this the file is a stale/partial download and gets replaced. */
    const val ROOTFS_MIN_VALID_BYTES = 50L * 1024 * 1024
    /** Free-space gate before expanding (compressed artifact + working room). */
    const val ROOTFS_MIN_FREE_BYTES = 1024L * 1024 * 1024
    const val KERNEL_ASSET = "guest/Image"
    const val KERNEL_FILE = "Image"
    const val VM_CONFIG_ASSET = "guest/sunshine-vm.json"
    const val VM_CONFIG_FILE = "sunshine-vm.json"

    fun vmDir(context: Context): File = File(context.filesDir, VM_DIR_NAME)
    fun bundleDir(context: Context): File = File(context.filesDir, BUNDLE_DIR_NAME)

    /** Android entry point — safe to call on any thread, never throws. */
    fun ensureProvisioned(context: Context): ProvisionResult {
        return try {
            val vm = vmDir(context).also { it.mkdirs() }
            val bundle = bundleDir(context).also { it.mkdirs() }
            ensureProvisionedDirs(
                assetNames = try {
                    context.assets.list("guest")?.toList() ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                },
                openAsset = { name ->
                    try {
                        context.assets.open(name)
                    } catch (_: Exception) {
                        null
                    }
                },
                vmDir = vm,
                bundleDir = bundle,
            )
        } catch (_: Exception) {
            ProvisionResult()
        }
    }

    /**
     * Pure-File core — no Context, fully unit-testable.
     * @param assetNames basenames listed under assets/guest (e.g. from AssetManager.list).
     * @param openAsset opens a full asset path like "guest/provision.sh", null if absent.
     */
    fun ensureProvisionedDirs(
        assetNames: List<String>,
        openAsset: (String) -> InputStream?,
        vmDir: File,
        bundleDir: File,
    ): ProvisionResult {
        val unpacked = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val missing = mutableListOf<String>()
        try {
            vmDir.mkdirs()
        } catch (_: Exception) {
        }
        try {
            bundleDir.mkdirs()
        } catch (_: Exception) {
        }

        // 1. Small guest bundle.
        for (name in BUNDLE_FILES) {
            val dest = File(bundleDir, name)
            try {
                if (dest.exists() && dest.length() > 0) {
                    skipped.add(name)
                    continue
                }
            } catch (_: Exception) {
            }
            // Only attempt copy when the packager actually listed it (avoids
            // log spam); fall back to a direct open attempt otherwise.
            if (assetNames.isNotEmpty() && !assetNames.contains(name)) {
                missing.add("guest/$name")
                continue
            }
            val inp = try {
                openAsset("guest/$name")
            } catch (_: Exception) {
                null
            }
            if (inp == null) {
                missing.add("guest/$name")
                continue
            }
            try {
                inp.use { src ->
                    dest.outputStream().use { out -> src.copyTo(out) }
                }
                unpacked.add(name)
            } catch (_: Exception) {
                try {
                    dest.delete()
                } catch (_: Exception) {
                }
            }
        }

        // 2. Optional compressed rootfs → expand + sparse-extend.
        when (val r = provisionRootfs(openAsset, vmDir)) {
            is RootfsResult.Ready -> skipped.add(ROOTFS_FILE)
            is RootfsResult.Unpacked -> unpacked.add(ROOTFS_FILE)
            is RootfsResult.Missing -> missing.add(r.message)
        }

        // 3. Optional kernel (uncompressed — must stay byte-identical for AVF).
        val kernelDest = File(vmDir, KERNEL_FILE)
        val kernelReady = try {
            kernelDest.exists() && kernelDest.length() > 0
        } catch (_: Exception) {
            false
        }
        if (kernelReady) {
            skipped.add(KERNEL_FILE)
        } else {
            val inp = try {
                openAsset(KERNEL_ASSET)
            } catch (_: Exception) {
                null
            }
            if (inp == null) {
                missing.add("$KERNEL_FILE (bundle $KERNEL_ASSET or manual download)")
            } else {
                try {
                    inp.use { src ->
                        kernelDest.outputStream().use { out -> src.copyTo(out) }
                    }
                    unpacked.add(KERNEL_FILE)
                } catch (_: Exception) {
                    try {
                        kernelDest.delete()
                    } catch (_: Exception) {
                    }
                    missing.add("$KERNEL_FILE (unpack failed)")
                }
            }
        }

        // 4. Optional VM config overlay.
        val cfgDest = File(vmDir, VM_CONFIG_FILE)
        val cfgReady = try {
            cfgDest.exists() && cfgDest.length() > 0
        } catch (_: Exception) {
            false
        }
        if (cfgReady) {
            skipped.add(VM_CONFIG_FILE)
        } else {
            val inp = try {
                openAsset(VM_CONFIG_ASSET)
            } catch (_: Exception) {
                null
            }
            if (inp == null) {
                // Config is truly optional — SshGuestTransport falls back to
                // filesDir defaults, so don't alarm. Still record it.
                missing.add("$VM_CONFIG_FILE (using defaults)")
            } else {
                try {
                    inp.use { src ->
                        cfgDest.outputStream().use { out -> src.copyTo(out) }
                    }
                    unpacked.add(VM_CONFIG_FILE)
                } catch (_: Exception) {
                    missing.add("$VM_CONFIG_FILE (unpack failed)")
                }
            }
        }

        return ProvisionResult(
            unpacked = unpacked,
            skippedExisting = skipped,
            missingOptional = missing,
        )
    }

    /** Outcome of the rootfs expand step (pure-File, unit-tested). */
    sealed interface RootfsResult {
        data object Ready : RootfsResult
        data object Unpacked : RootfsResult
        data class Missing(val message: String) : RootfsResult
    }

    /**
     * Expand the compressed rootfs artifact into [vmDir]/debian.img, then
     * sparse-extend to [ROOTFS_VIRTUAL_SIZE_BYTES].
     *
     * Source preference: xz (∼30% smaller) → gz (faster, no extra dep).
     * Streaming 8KB copy — peak RAM stays flat regardless of image size.
     * [onProgress] receives bytes written so a future downloader UI can show
     * MB progress; ignored by the silent first-run path.
     */
    fun provisionRootfs(
        openAsset: (String) -> InputStream?,
        vmDir: File,
        onProgress: (bytesWritten: Long) -> Unit = {},
        minValidBytes: Long = ROOTFS_MIN_VALID_BYTES,
        virtualSizeBytes: Long = ROOTFS_VIRTUAL_SIZE_BYTES,
    ): RootfsResult {
        val dest = File(vmDir, ROOTFS_FILE)
        try {
            if (dest.exists()) {
                if (dest.length() >= minValidBytes) return RootfsResult.Ready
                // Stale/partial download — drop it and try a clean expand.
                try {
                    dest.delete()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        // Free-space gate: fail with a clear message instead of dying mid-write.
        val free = try {
            vmDir.mkdirs()
            vmDir.usableSpace
        } catch (_: Exception) {
            -1L
        }
        if (free in 0 until ROOTFS_MIN_FREE_BYTES) {
            return RootfsResult.Missing(
                "$ROOTFS_FILE (need ~1GB free, have ${free / (1024 * 1024)}MB)",
            )
        }
        var stream: InputStream? = null
        var xz = false
        for (candidate in listOf(ROOTFS_ASSET_XZ to true, ROOTFS_ASSET_GZ to false)) {
            try {
                stream = openAsset(candidate.first)
            } catch (_: Exception) {
                stream = null
            }
            if (stream != null) {
                xz = candidate.second
                break
            }
        }
        if (stream == null) {
            return RootfsResult.Missing(
                "$ROOTFS_FILE (bundle $ROOTFS_ASSET_XZ/$ROOTFS_ASSET_GZ or manual download)",
            )
        }
        try {
            stream.use { raw ->
                val src: InputStream = if (xz) {
                    org.tukaani.xz.XZInputStream(raw)
                } else {
                    GZIPInputStream(raw)
                }
                src.use { inp ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(8192)
                        var written = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            try {
                                onProgress(written)
                            } catch (_: Exception) {
                            }
                        }
                        out.flush()
                    }
                }
            }
        } catch (_: Exception) {
            try {
                dest.delete()
            } catch (_: Exception) {
            }
            return RootfsResult.Missing("$ROOTFS_FILE (unpack failed — re-download or re-bundle)")
        }
        // Validity floor: catches truncated downloads before first boot.
        val finalLen = try {
            dest.length()
        } catch (_: Exception) {
            0L
        }
        if (finalLen < minValidBytes) {
            try {
                dest.delete()
            } catch (_: Exception) {
            }
            return RootfsResult.Missing("$ROOTFS_FILE (image too small — truncated artifact)")
        }
        // Sparse extend to the virtual size. Metadata-only on ext4/f2fs:
        // unwritten regions read as zeros and consume no flash.
        sparseExtend(dest, virtualSizeBytes)
        return RootfsResult.Unpacked
    }

    /**
     * Extend [file] to [targetBytes] without allocating blocks (sparse).
     * Never shrinks. Returns the final length, or -1 on failure.
     */
    fun sparseExtend(file: File, targetBytes: Long): Long {
        return try {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                if (raf.length() < targetBytes) raf.setLength(targetBytes)
                raf.length()
            }
        } catch (_: Exception) {
            -1L
        }
    }
}
