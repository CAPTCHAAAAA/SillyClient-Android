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
    val customRootsProvider: (() -> List<File>)? = null
) {
    val installationsDir: File get() = File(serversDir.parentFile ?: tarvenHome, "installations")
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
            dependenciesComplete = ::sharedDependenciesComplete
        )
    }

    /** True when dependencies resolve locally or through the shared dependency tree. */
    private fun sharedDependenciesComplete(directory: File): Boolean {
        if (File(directory, "node_modules").isDirectory) return true
        val lockKey = DependencyArchive(File(tarvenHome, "dependency-archives"))
            .lockKey(File(directory, "package-lock.json")) ?: return false
        return DependencyTrees(File(tarvenHome, "dependency-trees")).containsComplete(lockKey)
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

            // Prefer a public root when the user granted all-files access so instances are
            // visible in ordinary file managers; Android/data stays the unscoped fallback.
            val externalFiles = try { context.getExternalFilesDir(null) } catch (_: Exception) { null }
            val servers = when {
                android.os.Environment.isExternalStorageManager() ->
                    File(android.os.Environment.getExternalStorageDirectory(), "SillyClient/instances").apply { mkdirs() }
                externalFiles != null -> File(externalFiles, "instances").apply { mkdirs() }
                else -> legacyServers
            }

            val customRoots: () -> List<File> = {
                listOfNotNull(
                    externalFiles,
                    try { android.os.Environment.getExternalStorageDirectory() } catch (_: Exception) { null },
                    File("/storage/emulated/0"),
                    File("/storage"),
                    File("/sdcard")
                ).filter { it.exists() }
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
                customRootsProvider = customRoots
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
