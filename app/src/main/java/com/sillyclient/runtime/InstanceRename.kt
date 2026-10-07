package com.sillyclient.runtime

import java.io.File

/** A display-name change never changes authentication, cover, or snapshot ownership. */
class InstanceRename(private val paths: RuntimePaths, private val relocation: InstanceRelocation) {
    data class Result(val success: Boolean, val oldId: String, val newId: String, val oldPath: String, val newPath: String)

    fun rename(instanceId: String, newName: String, installPath: String? = null,
        operation: OperationCoordinator.Operation): Result {
        val name = newName.trim()
        require(name.isNotEmpty() && name != "." && name != "..") { "The new instance name is empty or invalid" }
        val id = RuntimePaths.normalizeInstanceId(instanceId)
        val source = paths.serverDirFor(id, installPath, create = false)
        val destination = File(requireNotNull(source.parentFile), RuntimePaths.normalizeInstanceId(name))
        val result = relocation.renameInPlace(id, destination.path, source.path, operation)
        return Result(result.success, id, id, result.oldPath, result.newPath)
    }
}
