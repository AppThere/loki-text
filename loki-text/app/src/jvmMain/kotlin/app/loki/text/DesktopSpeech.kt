package app.loki.text

/**
 * The desktop speaks through the engine's own Read Aloud player (macOS `say`, Speech Dispatcher or espeak, Windows'
 * synthesizer), so the app adds no voice of its own: two would read every sentence twice.
 */
actual fun platformSpeech(): Speech? = null
