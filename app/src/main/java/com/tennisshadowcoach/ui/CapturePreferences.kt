package com.tennisshadowcoach.ui

import android.content.Context
import com.tennisshadowcoach.analysis.CaptureCamera
import com.tennisshadowcoach.analysis.LeadingHand
import com.tennisshadowcoach.analysis.RecordingMetadata
import java.io.File

/**
 * Remembers the two facts the user chooses once: camera and leading hand.  Diagnostic fields
 * (declared view, phone side, manual counts) describe a single recording and are not kept.
 */
class CapturePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("capture", Context.MODE_PRIVATE)

    fun load(): RecordingMetadata = RecordingMetadata(
        camera = CaptureCamera.entries.firstOrNull { it.name == preferences.getString(KEY_CAMERA, null) }
            ?: CaptureCamera.UNSPECIFIED,
        leadingHand = LeadingHand.entries.firstOrNull { it.name == preferences.getString(KEY_HAND, null) }
    )

    fun save(metadata: RecordingMetadata) {
        preferences.edit()
            .putString(KEY_CAMERA, metadata.camera.takeIf { it != CaptureCamera.UNSPECIFIED }?.name)
            .putString(KEY_HAND, metadata.leadingHand?.name)
            .apply()
    }

    private companion object {
        const val KEY_CAMERA = "camera"
        const val KEY_HAND = "leading_hand"
    }
}

/** Recordings made by the app, newest first. */
fun savedRecordings(context: Context): List<File> =
    File(context.getExternalFilesDir(null), "TennisShadowCoach")
        .listFiles { file -> file.extension.equals("mp4", ignoreCase = true) }
        .orEmpty()
        .sortedByDescending(File::lastModified)
