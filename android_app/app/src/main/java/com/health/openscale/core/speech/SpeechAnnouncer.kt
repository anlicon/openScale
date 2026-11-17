/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.speech

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import androidx.annotation.StringRes
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Speaks short measurement feedback out loud.
 *
 * A body-fat weigh-in takes 10-20 s after the weight settles and the user is standing on the
 * scale, i.e. looking at their feet, not at the phone. Text-only feedback is therefore invisible
 * exactly when it matters, so the connection phases and the final weight are also announced.
 *
 * Design notes:
 *  - Deliberately a process-wide singleton: it is called from the BLE layer, which has no
 *    Context of its own and must not hold an Activity.
 *  - The TTS engine is created lazily on the first announcement. Creating it on app start costs
 *    100-300 ms and pulls in a service binding for a feature most sessions never use.
 *  - Everything runs on the main dispatcher: `TextToSpeech` is not documented as thread-safe and
 *    the Android engine expects to be driven from the thread that created it.
 *  - Announcements are dropped (not queued) while the engine is still starting up beyond a small
 *    buffer, so a slow engine cannot build a backlog that plays minutes later.
 *  - Every failure mode is non-fatal: no TTS engine, missing Chinese voice data, or the setting
 *    turned off all degrade to silence, never to a crash or a broken measurement.
 */
object SpeechAnnouncer {

    private const val TAG = "SpeechAnnouncer"

    /** How many announcements may wait for a still-initialising engine. */
    private const val MAX_PENDING = 3

    /** Engine creation is asynchronous; poll this many times (100 ms apart) before giving up. */
    private const val ENGINE_ATTEMPTS = 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var engine: TextToSpeech? = null
    private var engineRequested = false
    private var ready = false
    private var usable = true

    @Volatile private var enabled = true

    private val pending = ArrayDeque<String>()
    private val utteranceCounter = AtomicLong(0)

    /**
     * Binds the announcer to the application context and mirrors the user setting. Safe to call
     * more than once; only the first call has an effect.
     */
    fun initialize(context: Context, enabledFlow: Flow<Boolean>) {
        if (appContext != null) return
        appContext = context.applicationContext

        scope.launch {
            enabledFlow.distinctUntilChanged().collect { value ->
                enabled = value
                if (!value) {
                    pending.clear()
                    runCatching { engine?.stop() }
                }
            }
        }
    }

    /** Speaks a ready-made sentence. No-op when the setting is off or TTS is unusable. */
    fun speak(text: String) {
        if (text.isBlank()) return
        scope.launch {
            if (!enabled || !usable) return@launch
            if (!engineRequested) requestEngine()
            if (!ready || engine == null) {
                if (pending.size >= MAX_PENDING) pending.removeFirst()
                pending.addLast(text)
                return@launch
            }
            utter(text)
        }
    }

    /** Resolves [resId] against the application context and speaks it. */
    fun speak(@StringRes resId: Int, vararg args: Any) {
        val context = appContext ?: return
        val text = runCatching { context.getString(resId, *args) }.getOrNull() ?: return
        speak(text)
    }

    /**
     * Speaks two sentences in order. The normal [speak] path uses QUEUE_FLUSH so the newest
     * status always wins, which would cut the first sentence off the moment the second one is
     * submitted. Measurement completion is the one place where both matter, so the second
     * utterance is appended with QUEUE_ADD instead.
     */
    fun speakSequence(@StringRes first: Int, @StringRes second: Int, vararg args: Any) {
        val context = appContext ?: return
        val texts = listOfNotNull(
            runCatching { context.getString(first) }.getOrNull(),
            runCatching { context.getString(second, *args) }.getOrNull(),
        )
        if (texts.isEmpty()) return
        scope.launch {
            if (!enabled || !usable) return@launch
            if (!engineRequested) requestEngine()
            if (!ready || engine == null) {
                texts.forEach { text ->
                    if (pending.size >= MAX_PENDING) pending.removeFirst()
                    pending.addLast(text)
                }
                return@launch
            }
            texts.forEachIndexed { index, text ->
                utter(text, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD)
            }
        }
    }

    /** Creates the engine without speaking, e.g. so the settings screen can probe it. */
    fun ensureEngine() {
        if (!engineRequested) requestEngine()
    }

    /** True once the engine is initialised and has a usable voice. */
    fun isReady(): Boolean = ready

    /** True when the engine definitively cannot be used (no engine / no voice data). */
    fun isUnavailable(): Boolean = !usable

    /**
     * Speaks [resId] even when the setting is off - the user just flipped the switch and
     * expects to hear something. Also the cheapest way to prove the engine works.
     */
    fun speakSample(@StringRes resId: Int) {
        val context = appContext ?: return
        val text = runCatching { context.getString(resId) }.getOrNull() ?: return
        scope.launch {
            if (!usable) return@launch
            if (!engineRequested) requestEngine()
            if (!ready || engine == null) {
                pending.clear()
                pending.addLast(text)
                return@launch
            }
            utter(text)
        }
    }

    private fun requestEngine() {
        val app = appContext ?: return
        engineRequested = true
        engine = TextToSpeech(app) { status -> onEngineInitialized(status) }.also { tts ->
            applyAudioAttributes(tts)
        }
    }

    /**
     * Routes speech to the assistant stream. Without an explicit usage the engine may render
     * onto a stream the phone has muted or turned down (notification volume, "media" at 0 while
     * a call is active), which looks exactly like a broken announcer.
     *
     * Called again once the engine is initialised: whether the attributes survive the service
     * binding differs between engines, and the call is free, so setting them twice is cheaper
     * than debugging a silent app.
     */
    private fun applyAudioAttributes(tts: TextToSpeech) {
        runCatching {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
    }

    private fun onEngineInitialized(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            usable = false
            ready = false
            pending.clear()
            LogManager.w(
                TAG,
                "No usable TTS engine (status=$status, visible engines=${visibleEngineCount()}) - announcements disabled"
            )
            return
        }
        scope.launch { activateEngine() }
    }

    /**
     * How many TTS services this process is allowed to see, or -1 if it cannot be counted.
     *
     * Zero is the interesting case: the engine is installed but invisible to us, which is a
     * `<queries>` problem in the manifest and not a broken engine. `TextToSpeech.getEngines()`
     * cannot answer it - it only lists engines the same visibility rules already allowed.
     */
    @Suppress("DEPRECATION")
    private fun visibleEngineCount(): Int {
        val context = appContext ?: return -1
        return runCatching {
            context.packageManager.queryIntentServices(
                Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
                0
            ).size
        }.getOrDefault(-1)
    }

    private suspend fun activateEngine() {
        var tts = engine
        var attempts = 0
        while (tts == null && attempts++ < ENGINE_ATTEMPTS) {
            delay(100)
            tts = engine
        }
        if (tts == null) {
            usable = false
            pending.clear()
            LogManager.w(TAG, "TTS engine never became available - announcements disabled")
            return
        }

        // Prefer Simplified Chinese so "体重 65.3 公斤" is read in Chinese; fall back to the
        // device language when the Chinese voice data is not installed.
        val candidates = listOf(Locale.SIMPLIFIED_CHINESE, Locale.getDefault())
        val chosen = candidates.firstOrNull { locale ->
            val result = runCatching { tts.setLanguage(locale) }.getOrNull() ?: return@firstOrNull false
            result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (chosen == null) {
            usable = false
            pending.clear()
            LogManager.w(TAG, "TTS has no usable voice data - announcements disabled")
            return
        }

        runCatching {
            applyAudioAttributes(tts)
            tts.setSpeechRate(1.0f)
            tts.setPitch(1.0f)
        }
        ready = true
        LogManager.i(TAG, "TTS ready, language=$chosen")

        val queued = pending.toList()
        pending.clear()
        if (enabled) queued.forEach { utter(it) }
    }

    private fun utter(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        val tts = engine ?: return
        val id = "afu-speech-" + utteranceCounter.incrementAndGet()
        val result = runCatching {
            tts.speak(text, queueMode, null, id)
        }.getOrElse {
            LogManager.w(TAG, "speak() failed: ${it.message}")
            TextToSpeech.ERROR
        }
        if (result != TextToSpeech.SUCCESS) {
            LogManager.w(TAG, "TTS rejected utterance '$text' (result=$result)")
        } else {
            LogManager.d(TAG, "speaking: $text")
        }
    }
}
