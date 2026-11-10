package com.nowly.app.updates

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** Reported after a manual check only; an automatic check that finds nothing stays quiet. */
    data object Current : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val received: Long) : UpdateState
    /** The file matched the published checksum and is this app at the announced version. */
    data class Ready(val release: ReleaseInfo, val file: File) : UpdateState
    data class Failed(val message: String, val release: ReleaseInfo? = null) : UpdateState
}

val UpdateState.release: ReleaseInfo? get() = when (this) {
    is UpdateState.Available -> release
    is UpdateState.Downloading -> release
    is UpdateState.Ready -> release
    is UpdateState.Failed -> release
    else -> null
}

interface UpdateSettings {
    var autoCheck: Boolean
    var nextCheckAt: Long
    var skippedVersion: Int
}

private class UpdateException(message: String) : Exception(message)

/**
 * Finds, downloads and verifies a newer release. Installing stays with the system installer, which asks the
 * user to confirm and refuses a package signed with another key.
 */
class AppUpdater(
    /** False for builds that a published APK cannot replace, such as debug builds. */
    val enabled: Boolean,
    private val installedVersion: Int,
    val versionName: String,
    private val settings: UpdateSettings,
    private val transport: UpdateTransport,
    private val directory: File,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** The version code of an APK file when it is this app's package, otherwise null. */
    private val inspect: (File) -> Int?,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()
    private val _autoCheck = MutableStateFlow(settings.autoCheck)
    val autoCheck = _autoCheck.asStateFlow()
    private val _skipped = MutableStateFlow(settings.skippedVersion)
    /** A version the user chose not to be reminded about; it is still offered when they look for updates. */
    val skipped = _skipped.asStateFlow()
    private var download: Job? = null

    init {
        // Installers that were used, or were overtaken by the installed version, are no longer needed.
        scope.launch {
            directory.listFiles()?.forEach { file ->
                val version = packageFile.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
                if (version != null && version <= installedVersion) file.delete()
            }
        }
    }

    fun setAutoCheck(value: Boolean) {
        settings.autoCheck = value
        _autoCheck.value = value
    }

    /** Called when the app comes to the foreground; looks at most once per [INTERVAL_MILLIS]. */
    fun checkIfDue() {
        if (!enabled || !settings.autoCheck || now() < settings.nextCheckAt) return
        if (_state.compareAndSet(UpdateState.Idle, UpdateState.Checking)) scope.launch { check(manual = false) }
    }

    fun check() {
        val current = _state.value
        if (!enabled || current is UpdateState.Checking || current is UpdateState.Downloading) return
        if (_state.compareAndSet(current, UpdateState.Checking)) scope.launch { check(manual = true) }
    }

    private suspend fun check(manual: Boolean) {
        val found = try {
            UpdateManifest.parse(transport.text(UpdateManifest.URL, MANIFEST_LIMIT))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            settings.nextCheckAt = now() + RETRY_MILLIS
            _state.value = if (manual) UpdateState.Failed("暂时无法检查更新，请检查网络后重试。") else UpdateState.Idle
            return
        }
        settings.nextCheckAt = now() + INTERVAL_MILLIS
        _state.value = when {
            found != null && found.versionCode > installedVersion -> UpdateState.Available(found)
            manual -> UpdateState.Current
            else -> UpdateState.Idle
        }
    }

    fun download() {
        val current = _state.value
        val release = (current as? UpdateState.Available)?.release ?: (current as? UpdateState.Failed)?.release ?: return
        if (!_state.compareAndSet(current, UpdateState.Downloading(release, 0))) return
        // Started only after the job is recorded, so a cancel can always find it.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val target = File(directory, "nowly-${release.versionCode}.apk")
            val partial = File(directory, "${target.name}.part")
            try {
                directory.mkdirs()
                // A file kept from an earlier attempt is trusted only if it still matches the published checksum.
                if (!target.isFile || sha256(target) != release.sha256) {
                    val running = currentCoroutineContext()
                    transport.download(release.apkUrl, partial, release.size) { received ->
                        running.ensureActive()
                        _state.value = UpdateState.Downloading(release, received)
                    }
                    if (partial.length() != release.size || sha256(partial) != release.sha256) {
                        throw UpdateException("安装包校验未通过，已删除，请重试。")
                    }
                    target.delete()
                    if (!partial.renameTo(target)) throw IOException("Could not keep the verified download")
                }
                if (inspect(target) != release.versionCode) {
                    target.delete()
                    throw UpdateException("安装包与当前应用不匹配，已删除。")
                }
                _state.value = UpdateState.Ready(release, target)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _state.value = UpdateState.Failed((error as? UpdateException)?.message ?: "下载未完成，请检查网络后重试。", release)
            } finally { partial.delete() }
        }
        download = job
        job.start()
    }

    fun cancelDownload() {
        val running = download ?: return
        if (_state.value !is UpdateState.Downloading) return
        running.cancel()
        scope.launch {
            // Wait for the transfer to stop so that a late progress report cannot bring the state back.
            running.join()
            _state.update { if (it is UpdateState.Downloading) UpdateState.Available(it.release) else it }
        }
    }

    /** Stops reminding about the release on offer. */
    fun skip() {
        val version = _state.value.release?.versionCode ?: return
        settings.skippedVersion = version
        _skipped.value = version
    }

    fun installerUnavailable() {
        val release = _state.value.release ?: return
        _state.value = UpdateState.Failed("无法打开系统安装器，请从下载页安装。", release)
    }

    /** Clears a result that only answered a manual check, so the next look starts fresh. */
    fun acknowledge() {
        _state.update { if (it == UpdateState.Current || (it is UpdateState.Failed && it.release == null)) UpdateState.Idle else it }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
        const val RETRY_MILLIS = 60 * 60 * 1000L
        private const val MANIFEST_LIMIT = 64 * 1024
        private val packageFile = Regex("nowly-(\\d+)\\.apk(?:\\.part)?")
    }
}
