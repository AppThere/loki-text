package app.loki.text

actual fun systemLanguage(): String = java.util.Locale.getDefault().toLanguageTag()
