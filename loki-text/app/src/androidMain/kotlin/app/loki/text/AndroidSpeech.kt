package app.loki.text

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.atomic.AtomicInteger

/** Android's text-to-speech: one utterance at a time; the engine starts when first asked and speaks once it is ready. */
private class AndroidSpeech(context: android.content.Context) : Speech {
    private var ready = false
    private var pending: (() -> Unit)? = null
    private val ids = AtomicInteger()
    @Volatile private var waiting: Pair<String, () -> Unit>? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) pending?.invoke()
        pending = null
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                val w = waiting ?: return
                if (w.first == utteranceId) { waiting = null; w.second() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (waiting?.first == utteranceId) waiting = null }
        })
    }

    override fun speak(text: String, rate: Float, onDone: () -> Unit) {
        val go = {
            val id = "loki-${ids.incrementAndGet()}"
            waiting = id to onDone
            tts.setSpeechRate(rate.coerceIn(0.5f, 3f))
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
        if (ready) go() else pending = { go() }
    }

    override fun stop() {
        waiting = null
        pending = null
        if (ready) tts.stop()
    }
}

private var speech: Speech? = null

actual fun platformSpeech(): Speech? = speech ?: activityContext()?.let { AndroidSpeech(it) }?.also { speech = it }
