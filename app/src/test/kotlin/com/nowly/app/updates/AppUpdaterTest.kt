package com.nowly.app.updates

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

class AppUpdaterTest {
    private val apk = ByteArray(300_000) { (it % 251).toByte() }
    private val checksum = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val asset = "https://github.com/SandyKidYao/nowly/releases/download/v0.3.0/nowly.apk"
    private fun manifest(code: Int = 9, sha: String = checksum, size: Int = apk.size, url: String = asset) =
        """{"tag":"v0.3.0","versionCode":$code,"versionName":"0.3.0","apkUrl":"$url","sha256":"$sha","size":$size,"notes":" 修复提醒 ","later":1}"""

    private class Settings : UpdateSettings {
        override var autoCheck = true
        override var nextCheckAt = 0L
        override var skippedVersion = 0
    }

    private class Transport(var manifest: String?, val body: ByteArray) : UpdateTransport {
        var reads = 0
        var downloads = 0
        var onChunk: () -> Unit = {}
        override fun text(url: String, limit: Int): String { reads++; return manifest ?: throw IOException("offline") }
        override fun download(url: String, target: File, limit: Long, onBytes: (Long) -> Unit) {
            downloads++
            target.outputStream().use { output ->
                body.asList().chunked(100_000).fold(0L) { sent, chunk ->
                    output.write(chunk.toByteArray())
                    onChunk()
                    (sent + chunk.size).also(onBytes)
                }
            }
        }
    }

    private val directory = Files.createTempDirectory("nowly-updates").toFile()
    private val settings = Settings()
    private var clock = 1_000_000L
    private var packagedVersion: Int? = 9
    // Unconfined runs each step inline, so a call has finished its work by the time it returns.
    private fun updater(transport: Transport, enabled: Boolean = true, installed: Int = 8) = AppUpdater(enabled, installed, "0.2.6",
        settings, transport, directory, CoroutineScope(Dispatchers.Unconfined), { clock }) { packagedVersion }

    @Test fun manifestAcceptsOnlyThisProjectsReleaseAssets() {
        val release = UpdateManifest.parse(manifest())!!
        assertEquals(9, release.versionCode); assertEquals("0.3.0", release.versionName); assertEquals("修复提醒", release.notes)
        // The page deployed before in-app updates only names a tag.
        assertNull(UpdateManifest.parse("""{"tag":"v0.2.5"}"""))
        assertNull(UpdateManifest.parse("{}"))
        listOf(manifest(url = "https://example.com/nowly.apk"), manifest(url = "http://github.com/SandyKidYao/nowly/releases/download/v1/nowly.apk"),
            manifest(url = "https://github.com/SandyKidYao/nowly/releases/download/../../../other/app.apk"),
            manifest(sha = "abc"), manifest(sha = checksum.uppercase()), manifest(size = 0), manifest(code = -1), "not json").forEach { text ->
            assertTrue(text, runCatching { UpdateManifest.parse(text) }.isFailure)
        }
    }

    @Test fun automaticCheckIsQuietDailyAndRespectsTheSwitch() {
        val transport = Transport(manifest(code = 8), apk)
        val updater = updater(transport)
        updater.checkIfDue()
        assertEquals(UpdateState.Idle, updater.state.value); assertEquals(1, transport.reads)
        updater.checkIfDue()
        clock += AppUpdater.INTERVAL_MILLIS - 1
        updater.checkIfDue()
        assertEquals(1, transport.reads)
        clock += 1
        updater.setAutoCheck(false)
        updater.checkIfDue()
        assertEquals(1, transport.reads); assertFalse(settings.autoCheck)
        updater.setAutoCheck(true)
        transport.manifest = manifest()
        updater.checkIfDue()
        assertEquals(2, transport.reads)
        assertEquals(9, (updater.state.value as UpdateState.Available).release.versionCode)
        // A found release stays on offer; coming back to the app does not ask the network again.
        clock += AppUpdater.INTERVAL_MILLIS
        updater.checkIfDue()
        assertEquals(2, transport.reads)
    }

    @Test fun failedAutomaticCheckStaysSilentAndRetriesWithinTheHour() {
        val transport = Transport(null, apk)
        val updater = updater(transport)
        updater.checkIfDue()
        assertEquals(UpdateState.Idle, updater.state.value)
        clock += AppUpdater.RETRY_MILLIS - 1
        updater.checkIfDue()
        assertEquals(1, transport.reads)
        clock += 1
        updater.checkIfDue()
        assertEquals(2, transport.reads)
    }

    @Test fun manualCheckReportsItsResultAndBuildsThatCannotUpdateNeverAsk() {
        val transport = Transport(null, apk)
        val updater = updater(transport)
        updater.check()
        assertNull((updater.state.value as UpdateState.Failed).release)
        updater.acknowledge()
        assertEquals(UpdateState.Idle, updater.state.value)
        transport.manifest = """{"tag":"v0.2.5"}"""
        updater.check()
        assertEquals(UpdateState.Current, updater.state.value)
        updater.acknowledge()
        assertEquals(UpdateState.Idle, updater.state.value)

        val disabled = updater(Transport(manifest(), apk).also { other -> other.onChunk = { fail("no download") } }, enabled = false)
        disabled.checkIfDue(); disabled.check()
        assertEquals(UpdateState.Idle, disabled.state.value)
    }

    @Test fun downloadIsVerifiedReusedAndCleanedUpAfterInstalling() {
        val transport = Transport(manifest(), apk)
        val updater = updater(transport)
        val progress = mutableListOf<Long>()
        transport.onChunk = { (updater.state.value as? UpdateState.Downloading)?.let { progress += it.received } }
        updater.check(); updater.download()
        val ready = updater.state.value as UpdateState.Ready
        assertEquals("nowly-9.apk", ready.file.name); assertArrayEquals(apk, ready.file.readBytes())
        assertEquals(listOf(0L, 100_000L, 200_000L), progress)
        assertEquals(listOf("nowly-9.apk"), directory.list()!!.toList())
        // Looking again keeps the verified file instead of fetching it a second time.
        updater.check(); updater.download()
        assertTrue(updater.state.value is UpdateState.Ready); assertEquals(1, transport.downloads)
        // Once that version is installed its installer is removed.
        updater(transport, installed = 9)
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test fun tamperedOrForeignPackagesAreRejectedAndDeleted() {
        val transport = Transport(manifest(), apk.copyOf().also { it[5] = 0 })
        val updater = updater(transport)
        updater.check(); updater.download()
        val failed = updater.state.value as UpdateState.Failed
        assertEquals("安装包校验未通过，已删除，请重试。", failed.message); assertEquals(9, failed.release!!.versionCode)
        assertEquals(emptyList<String>(), directory.list()!!.toList())

        // A file with the right checksum that is not this app at the announced version is not offered either.
        packagedVersion = null
        val foreign = updater(Transport(manifest(), apk))
        foreign.check(); foreign.download()
        assertEquals("安装包与当前应用不匹配，已删除。", (foreign.state.value as UpdateState.Failed).message)
        assertEquals(emptyList<String>(), directory.list()!!.toList())

        // Retrying from the failure downloads again.
        packagedVersion = 9
        foreign.download()
        assertTrue(foreign.state.value is UpdateState.Ready)
    }

    @Test fun cancellingADownloadReturnsToTheOfferAndLeavesNoFile() {
        val transport = Transport(manifest(), apk)
        val updater = updater(transport)
        transport.onChunk = { updater.cancelDownload() }
        updater.check(); updater.download()
        assertEquals(9, (updater.state.value as UpdateState.Available).release.versionCode)
        assertEquals(emptyList<String>(), directory.list()!!.toList())
        transport.onChunk = {}
        updater.download()
        assertTrue(updater.state.value is UpdateState.Ready)
    }

    @Test fun interruptedDownloadCanBeRetriedAndSkippingOnlyHidesTheReminder() {
        val transport = Transport(manifest(), apk)
        val updater = updater(transport)
        transport.onChunk = { throw IOException("connection lost") }
        updater.check(); updater.download()
        assertEquals("下载未完成，请检查网络后重试。", (updater.state.value as UpdateState.Failed).message)
        assertEquals(emptyList<String>(), directory.list()!!.toList())
        updater.skip()
        assertEquals(9, updater.skipped.value); assertEquals(9, settings.skippedVersion)
        assertEquals(9, updater.state.value.release!!.versionCode)
        updater.acknowledge()
        assertTrue(updater.state.value is UpdateState.Failed)
        transport.onChunk = {}
        updater.download()
        assertTrue(updater.state.value is UpdateState.Ready)
    }
}
