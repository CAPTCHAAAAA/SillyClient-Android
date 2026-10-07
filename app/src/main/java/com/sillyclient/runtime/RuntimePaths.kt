package com.sillyclient.runtime

import android.content.Context
import java.io.File

data class RuntimePaths(
    val appFilesDir: File,
    val tarvenHome: File,
    val bootstrapDir: File,
    val serversDir: File,
    val usrDir: File,
    val usrLibDir: File,
    val tmpDir: File,
    val logsDir: File,
    val nativeLibDir: File,
    val nodeBin: File,
    val legacyServersDir: File? = null,
    val customRootsProvider: (() -> List<File>)? = null,
    val legacyExternalServersDir: File? = null
) {
    val installationsDir: File get() = File(tarvenHome, "installations")
    val instancePasswordsFile: File get() = File(tarvenHome, "instance-passwords.json")
    val instanceLock: InstanceLock by lazy { InstanceLock(instancePasswordsFile) }
    val installLocations: InstallLocationRegistry by lazy {
        InstallLocationRegistry(
            appFilesDir,
            serversDir,
            installationsDir,
            File(tarvenHome, "install-locations.json"),
            legacyServersDir,
            customRootsProvider,
            dependenciesComplete = { directory ->
                DependencyInstaller.hasRequiredPackages(directory) ||
                    DependencyBank.covers(directory, DependencyArchive(File(tarvenHome, "dependency-archives"))
                        .lockKey(File(directory, "package-lock.json")))
            }
        )
    }

    companion object {
        fun normalizeInstanceId(instanceId: String): String = InstallLocationRegistry.normalizeInstanceId(instanceId)

        fun from(context: Context): RuntimePaths {
            val files = context.filesDir
            val home = File(files, "tarven")
            val bootstrap = File(home, "bootstrap")
            val usr = File(home, "usr")
            val native = File(context.applicationInfo.nativeLibraryDir)
            val legacyServers = File(bootstrap, "servers")

            // The instances root is the folder the user chose in the creation
            // wizard (saved in app settings) so instances are plain folders any
            // file manager can browse; there is no software default root. The
            // app-private fallback keeps instances from the earlier managed-area
            // layout discoverable and launchable. The all-files management
            // permission is requested from the user when the folder is chosen,
            // never assumed.
            val externalFiles = try { context.getExternalFilesDir(null) } catch (_: Exception) { null }
            val settings = AppSettingsStore(AppSettingsStore.settingsFile(home)).load()
            val configuredRoot = settings.instancesRoot
                ?.takeIf { root -> root.startsWith("/") && !root.contains("://") }
                ?.let(::File)
            val servers = configuredRoot ?: File(files, "instances")
            // Legacy discovery candidates kept exactly as in build62: the
            // configured root, the public SillyClient/instances era and the
            // app-external dir, first one that exists wins.
            val legacyExternal = listOfNotNull(
                configuredRoot,
                File(android.os.Environment.getExternalStorageDirectory(), "SillyClient/instances"),
                externalFiles?.let { File(it, "instances") }
            ).firstOrNull { it.isDirectory }

            val customRoots: () -> List<File> = {
                val sharedRoots = listOfNotNull(
                    externalFiles,
                    try { android.os.Environment.getExternalStorageDirectory() } catch (_: Exception) { null },
                    File("/storage/emulated/0"),
                    File("/storage"),
                    File("/sdcard")
                ).filter { it.exists() }
                // Historical default instance locations stay accepted no matter which
                // root is configured now, and whether or not the directory currently
                // exists: instances created under them in earlier builds must remain
                // readable, launchable, deletable and migratable. Excluding them made
                // one such entry poison the whole registry read, so every scan,
                // migration or deletion failed (or crashed) afterwards.
                val legacyDefaults = listOfNotNull(
                    File(files, "instances"),
                    try { android.os.Environment.getExternalStorageDirectory() } catch (_: Exception) { null }
                        ?.let { File(it, "SillyClient/instances") },
                    externalFiles?.let { File(it, "instances") }
                )
                sharedRoots + legacyDefaults
            }

            return RuntimePaths(
                appFilesDir = files,
                tarvenHome = home,
                bootstrapDir = bootstrap,
                serversDir = servers,
                usrDir = usr,
                usrLibDir = File(usr, "lib"),
                tmpDir = File(home, "tmp"),
                logsDir = File(home, "logs"),
                nativeLibDir = native,
                nodeBin = File(native, "libtarven-node.so"),
                legacyServersDir = legacyServers,
                customRootsProvider = customRoots,
                legacyExternalServersDir = legacyExternal
            )
        }
    }

    fun ensureDirs() {
        listOf(
            tarvenHome,
            bootstrapDir,
            serversDir,
            installationsDir,
            usrDir,
            tmpDir,
            logsDir
        ).forEach {
            it.mkdirs()
        }
    }

    /** 多实例:返回指定 instanceId 的独立 server 目录。 */
    fun serverDirFor(instanceId: String, requestedPath: String? = null, create: Boolean = true, displayName: String? = null): File {
        val target = installLocations.resolve(instanceId, requestedPath, displayName = displayName)
        if (create) target.mkdirs()
        return target
    }

    fun launchDirectoryFor(instanceId: String, requestedPath: String?, installPathMode: String = "exact", displayName: String? = null): File =
        installLocations.resolve(instanceId, requestedPath, installPathMode, displayName)
}
