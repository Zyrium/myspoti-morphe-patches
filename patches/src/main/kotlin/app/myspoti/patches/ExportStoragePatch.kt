package app.myspoti.patches

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.template.patches.shared.Constants.ORPHEON_BETA

private const val EXT = "Lapp/myspoti/extension/DownloadPatch;"
private const val DOWNLOAD_TOOLS_ACTIVITY = "app.myspoti.extension.DownloadToolsActivity"

/**
 * The extension bundle contributes DEX classes, not host-app manifest entries.
 * Register DownloadToolsActivity in Myspoti's actual APK manifest so Android can launch it.
 */
private val downloadToolsActivityManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val applications = manifest.getElementsByTagName("application")
            require(applications.length > 0) { "Myspoti AndroidManifest.xml has no <application> element" }
            val application = applications.item(0) as org.w3c.dom.Element

            val existingActivities = manifest.getElementsByTagName("activity")
            val alreadyDeclared = (0 until existingActivities.length).any { index ->
                val activity = existingActivities.item(index) as? org.w3c.dom.Element
                activity?.getAttribute("android:name") == DOWNLOAD_TOOLS_ACTIVITY
            }

            if (!alreadyDeclared) {
                val activity = manifest.createElement("activity").apply {
                    setAttribute("android:name", DOWNLOAD_TOOLS_ACTIVITY)
                    setAttribute("android:exported", "false")
                    setAttribute("android:label", "Export & storage")
                }
                application.appendChild(activity)
            }
        }
    }
}

private val applicationOnCreate = Fingerprint(
    definingClass = "Lapp/myspoti/MyspotiApplication;",
    name = "onCreate",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/app/Application;",
            name = "onCreate",
            returnType = "V",
        ),
    ),
)

private val applicationCollectionsRead = Fingerprint(
    definingClass = "Lapp/myspoti/MyspotiApplication;",
    name = "onCreate",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(
            definingClass = "Ln66;",
            name = "g",
            returnType = "V",
            parameters = listOf("Ljava/lang/String;"),
        ),
    ),
)

private val offlineStoreLoad = Fingerprint(
    definingClass = "Ld86;",
    name = "o",
    returnType = "Ljava/util/ArrayList;",
    parameters = listOf("Ljava/io/File;"),
)

private val accountClaimLiveRoot = Fingerprint(
    definingClass = "Lh66;",
    name = "a",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Lg91;"),
    filters = listOf(
        string("offline"),
        methodCall(
            definingClass = "Ljava/io/File;",
            name = "<init>",
            returnType = "V",
            parameters = listOf("Ljava/io/File;", "Ljava/lang/String;"),
        ),
    ),
)

private val accountParkLiveRoot = Fingerprint(
    definingClass = "Lh66;",
    name = "e",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Lg91;"),
    filters = listOf(
        string("offline"),
        methodCall(
            definingClass = "Ljava/io/File;",
            name = "<init>",
            returnType = "V",
            parameters = listOf("Ljava/io/File;", "Ljava/lang/String;"),
        ),
    ),
)


private val settingsClassicScreen = Fingerprint(
    definingClass = "Ls28;",
    name = "p",
    returnType = "V",
    parameters = listOf("Lvz5;", "Lq11;", "I"),
    // The 1.0.0-beta.1 screen is a single composable with twelve section headers.
    // The sixth header (zero-based index 5) is Downloads.
    filters = List(12) {
        methodCall(
            definingClass = "Ls28;",
            name = "o",
            returnType = "V",
            parameters = listOf("I", "I", "Lq11;"),
        )
    },
)

private val immersiveSettingsPageRouter = Fingerprint(
    definingClass = "Lgz;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
    filters = listOf(
        methodCall(
            definingClass = "Lq04;",
            name = "h",
            returnType = "V",
            parameters = listOf("Lvz5;", "Lm03;", "Ll03;", "Lq11;", "I"),
        ),
    ),
)

context(_: BytecodePatchContext)
private fun applyMyspotiStorageHooks() {
    // OfflineStore.load(File) is staticized by R8. Its single File parameter is p0.
    // Replace that parameter before the original loader stores the root and scans indexes.
    offlineStoreLoad.method.addInstructions(
        0,
        """
            invoke-static {}, $EXT->storageRootNoArg()Ljava/io/File;
            move-result-object p0
        """.trimIndent(),
    )

    // Both account-switch routines inline File(filesDir, "offline"). In this APK's
    // coroutine state machines the Context and File receiver registers are stable:
    // claim = v12/v5, park = v13/v3. Replace only the just-created active root.
    accountClaimLiveRoot.method.addInstructions(
        accountClaimLiveRoot.instructionMatches.last().index + 1,
        """
            invoke-static {v12}, $EXT->storageRoot(Landroid/content/Context;)Ljava/io/File;
            move-result-object v5
        """.trimIndent(),
    )
    accountParkLiveRoot.method.addInstructions(
        accountParkLiveRoot.instructionMatches.last().index + 1,
        """
            invoke-static {v13}, $EXT->storageRoot(Landroid/content/Context;)Ljava/io/File;
            move-result-object v3
        """.trimIndent(),
    )

    // The application first reads collections.json from filesDir/offline. Repoint the
    // obfuscated OfflineCollections.file field and reload from the selected root after
    // that original read. Keep the app context available for storage helpers.
    applicationCollectionsRead.method.addInstructions(
        applicationCollectionsRead.instructionMatches.last().index + 1,
        """
            invoke-static/range {p0 .. p0}, $EXT->onAppReady(Landroid/content/Context;)V
        """.trimIndent(),
    )
    applicationOnCreate.method.addInstructions(
        applicationOnCreate.instructionMatches.first().index + 1,
        """
            invoke-static/range {p0 .. p0}, $EXT->install(Landroid/content/Context;)V
        """.trimIndent(),
    )


    val sectionHeaderCalls = settingsClassicScreen.instructionMatches
    require(sectionHeaderCalls.size == 12) {
        "Expected twelve Classic Settings section headers in Orpheon 1.0.0-beta.1, found ${sectionHeaderCalls.size}"
    }
    settingsClassicScreen.method.addInstructions(
        sectionHeaderCalls[5].index,
        """
            invoke-static {}, $EXT->showForCurrentActivity()V
        """.trimIndent(),
    )
    settingsClassicScreen.method.addInstructions(
        0,
        """
            invoke-static {}, $EXT->hideSettingsAction()V
        """.trimIndent(),
    )


    // The Settings page router is AnimatedContent's content lambda. Its first
    // argument is AnimatedContentScope and its second is the page enum. The Downloads
    // branch calls Lq04.h in 1.0.0-beta.1, which anchors this otherwise shared Function4 lambda.
    // Compose emits the target page last, after the outgoing page, so visibility follows
    // the destination rather than a page being disposed.
    immersiveSettingsPageRouter.method.addInstructions(
        0,
        """
            invoke-static/range {p2 .. p2}, $EXT->syncImmersiveSettingsAction(Ljava/lang/Object;)V
        """.trimIndent(),
    )

}

@Suppress("unused")
val myspotiExportStorageStable = bytecodePatch(
    name = "MySpoti Export & Storage",
    description = "Adds Export & storage to Settings > Downloads in Orpheon 1.0.0-beta.1 in both Classic and Immersive looks, matching Orpheon's Immersive glass, grouped surfaces and selected accent; includes tagged audio exports, offline lyrics resolution/cache (including romanization), portable LRC and JSON lyrics, embedded lyrics, playlist/album M3U8 manifests, reinstall restoration into native Downloads and lyrics caches, shared track files and offline storage migration while preserving Orpheon's native playback and AutoMix planner.",
    default = true,
) {
    compatibleWith(ORPHEON_BETA)
    dependsOn(downloadToolsActivityManifestPatch)
    extendWith("extensions/extension.mpe")
    execute { applyMyspotiStorageHooks() }
}
