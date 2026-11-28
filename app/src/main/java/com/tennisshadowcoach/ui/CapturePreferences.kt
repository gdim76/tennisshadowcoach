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

    fun loadFeedback(): MirrorFeedback = MirrorFeedback(
        voice = preferences.getBoolean(KEY_VOICE, true),
        framingPrompts = preferences.getBoolean(KEY_FRAMING, true),
        sound = preferences.getBoolean(KEY_SOUND, true),
        listen = preferences.getBoolean(KEY_LISTEN, true),
        voiceName = preferences.getString(KEY_VOICE_NAME, null)
    )

    fun saveFeedback(feedback: MirrorFeedback) {
        preferences.edit()
            .putBoolean(KEY_VOICE, feedback.voice)
            .putBoolean(KEY_FRAMING, feedback.framingPrompts)
            .putBoolean(KEY_SOUND, feedback.sound)
            .putBoolean(KEY_LISTEN, feedback.listen)
            .putString(KEY_VOICE_NAME, feedback.voiceName)
            .apply()
    }

    private companion object {
        const val KEY_CAMERA = "camera"
        const val KEY_HAND = "leading_hand"
        const val KEY_VOICE = "mirror_voice"
        const val KEY_FRAMING = "mirror_framing_prompts"
        const val KEY_SOUND = "mirror_sound"
        const val KEY_LISTEN = "mirror_listen"
        const val KEY_VOICE_NAME = "mirror_voice_name"
    }
}

/** Mirror feedback switches (M4), remembered between launches. */
data class MirrorFeedback(
    /** Speak corrections and calibration results. */
    val voice: Boolean = true,
    /** Also speak framing problems ("step back", "get into the frame"). */
    val framingPrompts: Boolean = true,
    /** Beep on each counted swing. */
    val sound: Boolean = true,
    /** Listen for voice commands (start/finish calibration, start/stop a set). */
    val listen: Boolean = true,
    /** Speech voice chosen by the user in the mirror settings; null until chosen. */
    val voiceName: String? = null
)

/** Recordings made by the app, newest first. */
fun savedRecordings(context: Context): List<File> =
    File(context.getExternalFilesDir(null), "TennisShadowCoach")
        .listFiles { file -> file.extension.equals("mp4", ignoreCase = true) }
        .orEmpty()
        .sortedByDescending(File::lastModified)
