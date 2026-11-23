package com.inditech.dpc.domain

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.inditech.dpc.data.model.IntegrityReport
import java.io.File

/**
 * Dynamic integrity checks: root signatures, bootloader unlock flags, debugging
 * features and known system-modifying packages. Any positive finding transitions
 * the device to HARD_LOCKED locally and flags it for risk review server-side.
 */
class IntegrityChecker(private val context: Context) {

    fun check(): IntegrityReport {
        val binaries = SU_PATHS.filter { File(it).exists() }
        val rootPackages = ROOT_PACKAGES.filter { isInstalled(it) }
        val testKeys = Build.TAGS?.contains("test-keys") == true
        val verifiedBoot = systemProperty("ro.boot.verifiedbootstate")
        val flashLocked = systemProperty("ro.boot.flash.locked")
        val bootloaderUnlocked = flashLocked == "0" || verifiedBoot == "orange"
        val adb = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        val devOptions = Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        return IntegrityReport(
            rooted = binaries.isNotEmpty() || rootPackages.isNotEmpty() || (testKeys && !isEmulator()),
            bootloaderUnlocked = bootloaderUnlocked && !isEmulator(),
            adbEnabled = adb && !isDebugBuild(),
            developerOptionsEnabled = devOptions,
            unauthorizedBinaries = binaries + rootPackages,
            verifiedBootState = verifiedBoot,
        )
    }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun isDebugBuild() = com.inditech.dpc.BuildConfig.DEBUG

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("sdk_gphone") || Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")

    @Suppress("PrivateApi")
    private fun systemProperty(key: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        (cls.getMethod("get", String::class.java).invoke(null, key) as String).ifBlank { null }
    } catch (_: Exception) {
        null
    }

    companion object {
        val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/system/bin/.ext/.su",
            "/system/usr/we-need-root/su-backup", "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
            "/system/app/Superuser.apk", "/cache/su", "/dev/su",
        )
        val ROOT_PACKAGES = listOf(
            "com.topjohnwu.magisk", "eu.chainfire.supersu", "com.koushikdutta.superuser", "com.noshufou.android.su",
            "com.kingroot.kinguser", "com.zachspong.temprootremovejb", "com.ramdroid.appquarantine", "me.weishu.kernelsu",
        )
    }
}
