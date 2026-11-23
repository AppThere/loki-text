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

actual fun documentSource(path: String): String? = null

actual fun recentAvailable(r: RecentDocument): Boolean = NSFileManager.defaultManager.fileExistsAtPath(r.path)

actual fun materializeRecent(r: RecentDocument): String? = r.path.takeIf { recentAvailable(r) }

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
