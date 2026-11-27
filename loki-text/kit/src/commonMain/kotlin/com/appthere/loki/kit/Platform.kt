package com.appthere.loki.kit

/** True on Android and iOS, false on the desktop. */
expect val platformIsTouch: Boolean

/**
 * True when the window is used with fingers and a pen (Android, iOS): the art workspace then shows
 * a compact top bar and a menu sheet instead of the desktop's text menu bar, and bigger targets.
 * The platform's answer; the screenshot harness may set it to draw the touch layout on a desktop.
 */
var isTouchPlatform: Boolean = platformIsTouch
