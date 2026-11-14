/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.hide.shorts

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.patch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/HideShortsToolbarPatch;"

/**
 * Looks up the top bar of the Shorts player (back button, title and the server provided
 * buttons such as chat, search and the overflow menu).
 */
internal object ShortsStaticHeaderFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "reel_static_header_group"),
        methodCall(name = "findViewById", location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

@Suppress("unused")
val hideShortsToolbarPatch = patch(
    name = "Hide Shorts toolbar",
    description = "Adds options to hide the top bar of the Shorts player and to hide the system status bar while watching Shorts.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playerTypeHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.addShortsPlayerPreferences(
            SwitchPreference("morphe_hide_shorts_toolbar", summary = true),
            SwitchPreference("morphe_hide_shorts_status_bar", summary = true),
        )

        ShortsStaticHeaderFingerprint.matchAll().forEach { match ->
            match.method.apply {
                val moveResultIndex = match.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

                addInstruction(
                    moveResultIndex + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS->hideShortsToolbar(Landroid/view/View;)V"
                )
            }
        }

        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->initialize(Landroid/app/Activity;)V",
        )
    }
}
