package app.loki.text

/** iOS has AVSpeechSynthesizer; this app doesn't drive it yet, so Read Aloud says there is no voice. */
actual fun platformSpeech(): Speech? = null
