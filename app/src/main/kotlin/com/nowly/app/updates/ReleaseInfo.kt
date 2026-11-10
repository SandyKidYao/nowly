package com.nowly.app.updates

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the product page publishes about the latest stable release. */
@Serializable
data class ReleaseInfo(
    val tag: String = "",
    val versionCode: Int = 0,
    val versionName: String = "",
    val apkUrl: String = "",
    val sha256: String = "",
    val size: Long = 0,
    val notes: String = "",
)

object UpdateManifest {
    const val URL = "https://sandykidyao.github.io/nowly/release.json"
    const val DOWNLOAD_PAGE = "https://sandykidyao.github.io/nowly/"
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    private const val MAX_NOTES = 2_000

    // The manifest may only point at this repository's release assets, whatever else it says.
    private const val ASSET_PREFIX = "https://github.com/SandyKidYao/nowly/releases/download/"
    private val digest = Regex("[0-9a-f]{64}")
    private val json = Json { ignoreUnknownKeys = true }

    /** Null when the page predates in-app updates and names no version to compare against. */
    fun parse(text: String): ReleaseInfo? {
        val release = json.decodeFromString<ReleaseInfo>(text)
        if (release.versionCode == 0) return null
        require(release.versionCode > 0 && release.versionName.isNotBlank()) { "Release version is invalid" }
        require(release.apkUrl.startsWith(ASSET_PREFIX) && ".." !in release.apkUrl) { "Release asset is not from this project" }
        require(digest.matches(release.sha256)) { "Release checksum is invalid" }
        require(release.size in 1..MAX_APK_BYTES) { "Release size is invalid" }
        return release.copy(notes = release.notes.trim().take(MAX_NOTES))
    }
}
