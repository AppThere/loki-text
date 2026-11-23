package app.loki.text

import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

private const val KEY = "app.loki.text.settings"

actual fun loadSettingsText(): String? = NSUserDefaults.standardUserDefaults.stringForKey(KEY)

actual fun saveSettingsText(text: String) {
    NSUserDefaults.standardUserDefaults.setObject(text, forKey = KEY)
}

actual fun fileExists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
