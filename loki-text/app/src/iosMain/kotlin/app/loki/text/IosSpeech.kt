@file:OptIn(ExperimentalForeignApi::class)

package app.loki.text

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMaximumSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMinimumSpeechRate
import platform.AVFAudio.setActive
import platform.darwin.NSObject

/** The system voice (AVSpeechSynthesizer): one utterance at a time, in the system language's default voice. */
private class IosSpeech : Speech {
    private val synth = AVSpeechSynthesizer()
    // The utterance being spoken and what to call when it ends; a stopped utterance never calls it.
    private var current: Pair<AVSpeechUtterance, () -> Unit>? = null
    private var audioReady = false

    private val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            val c = current ?: return
            if (c.first === didFinishSpeechUtterance) { current = null; c.second() }
        }
    }

    init {
        synth.delegate = delegate
    }

    override fun speak(text: String, rate: Float, onDone: () -> Unit) {
        // Read Aloud is the point of the app's sound: it plays with the ring switch on silent, as spoken audio.
        if (!audioReady) {
            val session = AVAudioSession.sharedInstance()
            audioReady = session.setCategory(AVAudioSessionCategoryPlayback, mode = AVAudioSessionModeSpokenAudio, options = 0u, error = null) &&
                session.setActive(true, error = null)
        }
        val u = AVSpeechUtterance.speechUtteranceWithString(text)
        u.rate = (AVSpeechUtteranceDefaultSpeechRate * rate).coerceIn(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceMaximumSpeechRate)
        current = null
        synth.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        current = u to onDone
        synth.speakUtterance(u)
    }

    override fun stop() {
        current = null
        synth.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }
}

private var speech: Speech? = null

actual fun platformSpeech(): Speech? = speech ?: IosSpeech().also { speech = it }
