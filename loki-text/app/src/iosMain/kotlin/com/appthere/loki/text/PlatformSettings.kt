@file:OptIn(ExperimentalForeignApi::class)

package com.appthere.loki.text

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970

private const val KEY = "com.appthere.loki.text.settings"
private const val BOOKMARKS = "com.appthere.loki.text.bookmarks"

/** Application Support, created on first use: iOS doesn't make it until an app asks. */
actual fun profileDir(): String? {
    val dir = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).firstOrNull() as? String ?: return null
    return dir.takeIf { ensureDir(it) }
}

actual fun loadSettingsText(): String? = NSUserDefaults.standardUserDefaults.stringForKey(KEY)

actual fun saveSettingsText(text: String) {
    NSUserDefaults.standardUserDefaults.setObject(text, forKey = KEY)
}

/** Bookmarks of picked documents, by URL: what lets a later run read a document outside the app again. */
private fun bookmarks(): Map<Any?, *> = NSUserDefaults.standardUserDefaults.dictionaryForKey(BOOKMARKS).orEmpty()

/**
 * The URL of the document the user picked that [path] is a copy of. Its bookmark is kept too, so the recent list can
 * reopen it after a restart.
 */
actual fun documentSource(path: String): String? {
    val url = pickedSource(path) ?: return null
    val source = url.absoluteString ?: return null
    // A document outside the app gives a bookmark only while its scope is open.
    val mark = scoped(url) { url.bookmarkDataWithOptions(0u, includingResourceValuesForKeys = null, relativeToURL = null, error = null) }
    if (mark != null) NSUserDefaults.standardUserDefaults.setObject(bookmarks() + (source to mark), forKey = BOOKMARKS)
    return source
}

/** Where [r]'s document is now, from its bookmark; null when it has none or the document has gone. */
private fun resolve(r: RecentDocument): NSURL? {
    val mark = bookmarks()[r.uri ?: return null] as? NSData ?: return null
    return NSURL.URLByResolvingBookmarkData(mark, options = 0u, relativeToURL = null, bookmarkDataIsStale = null, error = null)
}

actual fun recentAvailable(r: RecentDocument): Boolean = resolve(r) != null || NSFileManager.defaultManager.fileExistsAtPath(r.path)

/** A fresh copy of [r]'s document where its bookmark still resolves (so Save writes back to it), else the staged file. */
actual fun materializeRecent(r: RecentDocument): String? =
    resolve(r)?.let { stage(it) } ?: r.path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
