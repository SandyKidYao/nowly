package com.nowly.app.updates

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import kotlinx.coroutines.CoroutineScope

private class StoredUpdateSettings(context: Context) : UpdateSettings {
    private val store = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    override var autoCheck: Boolean
        get() = store.getBoolean("auto_check", true)
        set(value) = store.edit { putBoolean("auto_check", value) }
    override var nextCheckAt: Long
        get() = store.getLong("next_check_at", 0)
        set(value) = store.edit { putLong("next_check_at", value) }
    override var skippedVersion: Int
        get() = store.getInt("skipped_version", 0)
        set(value) = store.edit { putInt("skipped_version", value) }
}

object AndroidUpdates {
    private const val APK_TYPE = "application/vnd.android.package-archive"

    fun create(context: Context, scope: CoroutineScope): AppUpdater {
        val packages = context.packageManager
        val installed = packages.getPackageInfo(context.packageName, 0)
        // A debug build is a different package with a different key; a published APK can never replace it.
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return AppUpdater(
            enabled = !debuggable,
            installedVersion = PackageInfoCompat.getLongVersionCode(installed).toInt(),
            versionName = installed.versionName.orEmpty(),
            settings = StoredUpdateSettings(context),
            transport = HttpUpdateTransport(),
            directory = File(context.cacheDir, "updates"),
            scope = scope,
        ) { file ->
            packages.getPackageArchiveInfo(file.path, 0)?.takeIf { it.packageName == context.packageName }
                ?.let { PackageInfoCompat.getLongVersionCode(it).toInt() }
        }
    }

    /** Hands the verified file to the system installer, which asks the user before replacing the app. */
    fun installIntent(context: Context, file: File): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.updates", file), APK_TYPE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}
