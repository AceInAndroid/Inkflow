package com.aceinandroid.inkflow

import com.aceinandroid.inkflow.core.sync.ReadingAgentDelta
import com.aceinandroid.inkflow.core.sync.VocabularyDelta
import java.io.File
import java.io.IOException
import java.util.UUID

internal data class IncrementalSyncReport(val merged: Int = 0, val uploaded: Int = 0,
    val conflicts: Int = 0, val ignored: Int = 0) {
    operator fun plus(other: IncrementalSyncReport) = IncrementalSyncReport(merged + other.merged,
        uploaded + other.uploaded, conflicts + other.conflicts, ignored + other.ignored)
}

/** Network protocol only; the runner retains its whole-run gate and the store its atomic transactions. */
internal class NativeIncrementalSyncBranch(private val client: WebDavClient, private val device: String,
    private val work: File) {
    private var packages = 0
    private var downloadedBytes = 0L

    fun synchronize(directory: String, key: String?, merge: (NativeIncrementalSyncPackage) -> Int,
        capture: () -> NativeIncrementalSyncPackage): IncrementalSyncReport {
        client.ensureDirectory(directory)
        val resources = client.resources(directory).filter { !it.directory && it.name.endsWith(".json") }
        require(resources.size <= 64) { "Incremental sync device limit exceeded" }
        val ownName = "$device.json"
        val own = resources.firstOrNull { it.name == ownName }
        var protectOwn = false
        var merged = 0; var conflicts = 0; var ignored = 0
        for (resource in resources) {
            if (!NativeIncrementalSyncPackage.validDevice(resource.name.removeSuffix(".json"))) {
                ignored++; continue
            }
            require(++packages <= 256 && downloadedBytes < MAX_RUN_BYTES) { "Incremental sync run limit exceeded" }
            if (resource.size != null && (resource.size < 0 || resource.size > ReadingAgentDelta.MAX_BYTES)) {
                if (resource.name == ownName) protectOwn = true
                ignored++; continue
            }
            val file = File(work, UUID.randomUUID().toString())
            try {
                // Transport failures escape; malformed branch data is isolated below.
                client.download("$directory/${resource.name}", file,
                    minOf(ReadingAgentDelta.MAX_BYTES.toLong(), MAX_RUN_BYTES - downloadedBytes))
                downloadedBytes += file.length()
                try {
                    val data = NativeIncrementalSyncPackage.decode(file.readText(Charsets.UTF_8))
                    require(data.deviceId + ".json" == resource.name && data.bookKey == key)
                    require(data.type == if (key == null) VocabularyDelta.TYPE else ReadingAgentDelta.TYPE)
                    conflicts += merge(data)
                    merged++
                } catch (_: IOException) {
                    if (resource.name == ownName) protectOwn = true
                    ignored++
                } catch (_: IllegalArgumentException) {
                    if (resource.name == ownName) protectOwn = true
                    ignored++
                }
            } finally { file.delete() }
        }
        if (protectOwn || own != null && own.etag.isNullOrBlank())
            return IncrementalSyncReport(merged, conflicts = conflicts + 1, ignored = ignored)
        val file = File(work, "upload.json")
        var uploaded = 0
        try {
            file.writeText(capture().encode(), Charsets.UTF_8)
            try {
                client.upload("$directory/$ownName", file,
                    own?.etag?.let { WebDavWriteCondition.Match(it) } ?: WebDavWriteCondition.Absent)
                uploaded = 1
            } catch (failure: IOException) {
                if (failure.message?.let { it.endsWith("(HTTP 412)") || it == "WebDAV PUT failed: 412" } == true)
                    conflicts++ else throw failure
            }
        } finally { file.delete() }
        return IncrementalSyncReport(merged, uploaded, conflicts, ignored)
    }

    private companion object { const val MAX_RUN_BYTES = 32L * 1024 * 1024 }
}
