// Sunshine VmProvisioner — silent first-run setup for the Debian pVM.
// Runs on app startup (SunshineApp) + MainActivity ViewModel creation so the
// user never has to tap Boot/Provision manually on first launch.
//
// What it does (best-effort, never throws):
//   1. Ensures filesDir/sunshine-vm + filesDir/guest-bundle exist.
//   2. Unpacks the small guest bundle (assets/guest/* → guest-bundle/).
//   3. Unpacks optional large assets if the packager bundled them:
//        assets/guest/debian.img.gz → sunshine-vm/debian.img (gunzipped)
//        assets/guest/Image         → sunshine-vm/Image
//        assets/guest/sunshine-vm.json → sunshine-vm/sunshine-vm.json
//      These are NOT in the repo (too large) — when absent they are reported
//      in ProvisionResult.missingOptional so the UI can show a calm hint
//      instead of crashing. A future build can bundle or download them.
//
// The pure-File core (ensureProvisionedDirs) is unit-tested without Android.
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
        "sunshine-agent.slice",
        "nftables-sunshine.nft",
    )

    const val ROOTFS_ASSET_GZ = "guest/debian.img.gz"
    const val ROOTFS_FILE = "debian.img"
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

        // 2. Optional large rootfs (gzipped to keep the APK small).
        val rootfsDest = File(vmDir, ROOTFS_FILE)
        val rootfsReady = try {
            rootfsDest.exists() && rootfsDest.length() > 0
        } catch (_: Exception) {
            false
        }
        if (rootfsReady) {
            skipped.add(ROOTFS_FILE)
        } else {
            val gz = try {
                openAsset(ROOTFS_ASSET_GZ)
            } catch (_: Exception) {
                null
            }
            if (gz == null) {
                missing.add("$ROOTFS_FILE (bundle $ROOTFS_ASSET_GZ or manual download)")
            } else {
                try {
                    gz.use { raw ->
                        GZIPInputStream(raw).use { src ->
                            rootfsDest.outputStream().use { out -> src.copyTo(out) }
                        }
                    }
                    unpacked.add(ROOTFS_FILE)
                } catch (_: Exception) {
                    try {
                        rootfsDest.delete()
                    } catch (_: Exception) {
                    }
                    missing.add("$ROOTFS_FILE (unpack failed)")
                }
            }
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
}
