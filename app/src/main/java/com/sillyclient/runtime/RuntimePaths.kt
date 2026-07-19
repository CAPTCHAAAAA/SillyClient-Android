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
    val nodeBin: File
) {
    companion object {
        fun from(context: Context): RuntimePaths {
            val files = context.filesDir
            val home = File(files, "tarven")
            val bootstrap = File(home, "bootstrap")
            val usr = File(home, "usr")
            val native = File(context.applicationInfo.nativeLibraryDir)

            return RuntimePaths(
                appFilesDir = files,
                tarvenHome = home,
                bootstrapDir = bootstrap,
                serversDir = File(bootstrap, "servers"),
                usrDir = usr,
                usrLibDir = File(usr, "lib"),
                tmpDir = File(home, "tmp"),
                logsDir = File(home, "logs"),
                nativeLibDir = native,
                nodeBin = File(native, "libtarven-node.so")
            )
        }
    }

    fun ensureDirs() {
        listOf(
            tarvenHome,
            bootstrapDir,
            serversDir,
            usrDir,
            tmpDir,
            logsDir
        ).forEach { it.mkdirs() }
    }

    /** 多实例:返回指定 instanceId 的独立 server 目录。 */
    fun serverDirFor(instanceId: String, create: Boolean = true): File {
        val safeId = instanceId
            .trim()
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "-")
            .trim('.', '_', '-')
            .take(80)
            .ifBlank { "default" }
        val dir = File(serversDir, safeId)
        if (create) dir.mkdirs()
        return dir
    }
}
