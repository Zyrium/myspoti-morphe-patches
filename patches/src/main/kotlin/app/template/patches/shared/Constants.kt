package app.template.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val ORPHEON_BETA = Compatibility(
        name = "Orpheon",
        packageName = "app.orpheon.beta",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x1DB954,
        targets = listOf(AppTarget(version = "1.0.0-beta.1", isExperimental = false)),
    )
}
