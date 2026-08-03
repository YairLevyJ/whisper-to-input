/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2025 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.whispertoinput.keyboard.WhisperKeyboard
import com.example.whispertoinput.recorder.RecorderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val RECORDED_AUDIO_FILENAME_WAV = "recorded.wav"
private const val AUDIO_MEDIA_TYPE_WAV = "audio/wav"
private const val IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL = 28
private val TRANSCRIPTION_MODE_CYCLE =
    listOf(TRANSCRIPTION_MODE_API, TRANSCRIPTION_MODE_LOCAL, TRANSCRIPTION_MODE_AUTO)

class WhisperInputService : InputMethodService() {
    private val whisperKeyboard: WhisperKeyboard = WhisperKeyboard()
    private val transcriptionCoordinator: TranscriptionCoordinator = TranscriptionCoordinator()
    private var recorderManager: RecorderManager? = null
    private var recordedAudioFilename: String = ""
    private var useVoiceActivityDetection: Boolean = false
    private var useAudioEffects: Boolean = false
    private var isFirstTime: Boolean = true

    // The UI language the current input view was inflated with, so that a language change made in
    // the settings can be picked up without waiting for the service itself to be recreated.
    private var inputViewLanguage: String? = null

    // Applies the selected UI language to everything this service resolves, keyboard included.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyToContext(newBase))
    }

    private fun transcriptionCallback(text: String?) {
        if (!text.isNullOrEmpty()) {
            CoroutineScope(Dispatchers.Main).launch {
                val shortcuts = dataStore.data.map { preferences: Preferences ->
                    TextShortcuts.parse(preferences[TEXT_SHORTCUTS] ?: "")
                }.first()
                currentInputConnection?.commitText(TextShortcuts.apply(text, shortcuts), 1)

                // Check if auto-switch-back is enabled and switch if so
                val autoSwitchBack = dataStore.data.map { preferences: Preferences ->
                    preferences[AUTO_SWITCH_BACK] ?: false
                }.first()
                if (autoSwitchBack) {
                    onSwitchIme()
                }
            }
        }
        whisperKeyboard.reset()
    }

    private fun transcriptionExceptionCallback(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        whisperKeyboard.reset()
    }

    private fun transcriptionNoticeCallback(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun labelForTranscriptionMode(mode: String): String = when (mode) {
        TRANSCRIPTION_MODE_LOCAL -> getString(R.string.keyboard_transcription_mode_local)
        TRANSCRIPTION_MODE_AUTO -> getString(R.string.keyboard_transcription_mode_auto)
        else -> getString(R.string.keyboard_transcription_mode_api)
    }

    private fun refreshTranscriptionModeLabel() {
        CoroutineScope(Dispatchers.Main).launch {
            val mode = dataStore.data.map { preferences: Preferences ->
                preferences[TRANSCRIPTION_MODE] ?: TRANSCRIPTION_MODE_API
            }.first()
            whisperKeyboard.setTranscriptionModeLabel(labelForTranscriptionMode(mode))
        }
    }

    private fun onCycleTranscriptionMode() {
        CoroutineScope(Dispatchers.Main).launch {
            val current = dataStore.data.map { preferences: Preferences ->
                preferences[TRANSCRIPTION_MODE] ?: TRANSCRIPTION_MODE_API
            }.first()
            val next = TRANSCRIPTION_MODE_CYCLE[
                (TRANSCRIPTION_MODE_CYCLE.indexOf(current) + 1) % TRANSCRIPTION_MODE_CYCLE.size
            ]
            dataStore.edit { settings -> settings[TRANSCRIPTION_MODE] = next }
            whisperKeyboard.setTranscriptionModeLabel(labelForTranscriptionMode(next))
        }
    }

    private suspend fun updateRecordingSettings() {
        useVoiceActivityDetection = dataStore.data.map { preferences: Preferences ->
            preferences[AUTO_STOP_RECORDING] ?: false
        }.first()
        useAudioEffects = dataStore.data.map { preferences: Preferences ->
            preferences[AUDIO_EFFECTS] ?: false
        }.first()
    }

    override fun onCreateInputView(): View {
        // Initialize members with regard to this context
        recorderManager = RecorderManager(this)

        // All backends receive the same 16 kHz mono PCM WAV file, so the path is fixed.
        // It must be assigned before the keyboard is set up, since setup() queries shouldShowRetry().
        // The internal cache directory is used rather than the external one so that the recording
        // is never readable by other apps.
        recordedAudioFilename = "${cacheDir.absolutePath}/${RECORDED_AUDIO_FILENAME_WAV}"

        // Initialize recording behavior based on settings
        CoroutineScope(Dispatchers.Main).launch {
            updateRecordingSettings()
        }
        refreshTranscriptionModeLabel()

        // Should offer ime switch?
        val shouldOfferImeSwitch: Boolean =
            if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
                shouldOfferSwitchingToNextInputMethod()
            } else {
                val inputMethodManager =
                    getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val token: IBinder? = window?.window?.attributes?.token
                inputMethodManager.shouldOfferSwitchingToNextInputMethod(token)
            }

        // Sets up recorder manager
        recorderManager!!.setOnUpdateMicrophoneAmplitude { amplitude ->
            onUpdateMicrophoneAmplitude(amplitude)
        }
        // Voice activity detection drives the same keyboard transitions as the mic/cancel buttons,
        // so the FSM stays the single source of truth for the keyboard state.
        recorderManager!!.setOnAutoStopRecording {
            whisperKeyboard.tryStartTranscribing("")
        }
        recorderManager!!.setOnAutoCancelRecording {
            whisperKeyboard.tryCancelRecording()
        }

        // Returns the keyboard after setting it up and inflating its layout.
        // The inflater is rebuilt from a locale-aware context on every call, so that rebuilding
        // the input view is enough to switch the keyboard's language.
        inputViewLanguage = LocaleHelper.getLanguage(this)
        val inflater = LayoutInflater.from(LocaleHelper.applyToContext(this))
        return whisperKeyboard.setup(inflater,
            shouldOfferImeSwitch,
            { onStartRecording() },
            { onCancelRecording() },
            { attachToEnd -> onStartTranscription(attachToEnd) },
            { onCancelTranscription() },
            { onDeleteText() },
            { onEnter() },
            { onSpaceBar() },
            { onSwitchIme() },
            { onOpenSettings() },
            { shouldShowRetry() },
            { onCycleTranscriptionMode() },
        )
    }

    private fun onStartRecording() {
        // Upon starting recording, check whether audio permission is granted.
        if (!recorderManager!!.allPermissionsGranted(this)) {
            // If not, launch app MainActivity (for permission setup).
            launchMainActivity()
            whisperKeyboard.reset()
            return
        }

        recorderManager!!.start(
            recordedAudioFilename,
            useVoiceActivityDetection,
            useAudioEffects
        )
    }

    // when mic amplitude is updated, notify the keyboard
    // this callback is registered to the recorder manager
    private fun onUpdateMicrophoneAmplitude(amplitude: Int) {
        whisperKeyboard.updateMicrophoneAmplitude(amplitude)
    }

    private fun onCancelRecording() {
        recorderManager!!.stop()
    }

    private fun onStartTranscription(attachToEnd: String) {
        recorderManager!!.stop()
        transcriptionCoordinator.startAsync(this,
            recordedAudioFilename,
            AUDIO_MEDIA_TYPE_WAV,
            attachToEnd,
            { transcriptionCallback(it) },
            { transcriptionExceptionCallback(it) },
            { transcriptionNoticeCallback(it) })
    }

    private fun onCancelTranscription() {
        transcriptionCoordinator.stop()
    }

    private fun onDeleteText() {
        val inputConnection = currentInputConnection ?: return
        val selectedText = inputConnection.getSelectedText(0)

        // Deletes cursor pointed text, or all selected texts
        if (TextUtils.isEmpty(selectedText)) {
            inputConnection.deleteSurroundingText(1, 0)
        } else {
            inputConnection.commitText("", 1)
        }
    }

    private fun onSwitchIme() {
        // Before API Level 28, switchToPreviousInputMethod() was not available
        if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
            switchToPreviousInputMethod()
        } else {
            val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val token: IBinder? = window?.window?.attributes?.token
            inputMethodManager.switchToLastInputMethod(token)
        }

    }

    private fun onOpenSettings() {
        launchMainActivity()
    }

    private fun onEnter() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    }

    private fun onSpaceBar() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.commitText(" ", 1)
    }

    private fun shouldShowRetry(): Boolean {
        val exists = File(recordedAudioFilename).exists()
        return exists
    }

    // Opens up app MainActivity
    private fun launchMainActivity() {
        val dialogIntent = Intent(this, MainActivity::class.java)
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(dialogIntent)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        transcriptionCoordinator.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()

        // Rebuild the keyboard if the UI language was changed in the settings while this service
        // was already running.
        if (inputViewLanguage != null && inputViewLanguage != LocaleHelper.getLanguage(this)) {
            setInputView(onCreateInputView())
        }

        // Picks up a transcription mode change made in settings since the keyboard was last shown.
        refreshTranscriptionModeLabel()

        // If this is the first time calling onWindowShown, it means this IME is just being switched to.
        // Automatically starts recording after switching to Whisper Input. (if settings enabled)
        // Dispatch a coroutine to do this task.
        CoroutineScope(Dispatchers.Main).launch {
            // Pick up any settings changed since the keyboard was last shown
            updateRecordingSettings()
            if (!isFirstTime) return@launch
            isFirstTime = false
            val isAutoStartRecording = dataStore.data.map { preferences: Preferences ->
                preferences[AUTO_RECORDING_START] ?: true
            }.first()
            if (isAutoStartRecording) {
                whisperKeyboard.tryStartRecording()
            }
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        transcriptionCoordinator.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        transcriptionCoordinator.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()
    }
}
