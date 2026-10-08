package com.sillyclient.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.sillyclient.runtime.ManagedFiles
import com.sillyclient.runtime.RuntimePaths
import java.io.File

/** Public instance storage is opt-in; existing app-owned instances remain accessible. */
object InstanceStorageAccess {
    const val LEGACY_PERMISSION_ALIAS = "instanceStorage"
    const val DENIED_MESSAGE = "未获得存储权限，未创建或迁移实例。请授权后重试；现有实例仍保留在原位置。"

    fun usesAllFilesAccess(sdk: Int): Boolean = sdk >= 30

    fun isGranted(context: Context): Boolean = if (usesAllFilesAccess(Build.VERSION.SDK_INT)) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun requiresPublicAccess(paths: RuntimePaths, directory: File): Boolean =
        listOfNotNull(paths.appFilesDir, paths.legacyExternalServersDir?.parentFile).none { root ->
            directory.canonicalFile == root.canonicalFile || ManagedFiles.isWithin(directory, root)
        }

    fun requireAccess(context: Context, paths: RuntimePaths, directory: File) {
        check(!requiresPublicAccess(paths, directory) || isGranted(context)) { DENIED_MESSAGE }
    }
}
