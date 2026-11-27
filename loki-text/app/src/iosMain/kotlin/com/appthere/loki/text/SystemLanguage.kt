package com.appthere.loki.text

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

actual fun systemLanguage(): String = (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en"
