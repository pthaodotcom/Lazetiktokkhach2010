package com.perkompus.laze

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest
import java.util.Locale

/** Unknown layouts produce missing evidence, never permission to swipe blindly. */
class TikTokScreenReader {
    companion object { const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically" }

    fun read(root: AccessibilityNodeInfo?, marker: String, width: Int, height: Int): ScreenSample {
        if (root?.packageName?.toString() != TIKTOK_PACKAGE) return ScreenSample(false)
        val ranges = mutableListOf<Float>()
        val identities = sortedSetOf<String>()
        var target = false
        var blocker: String? = null
        var visited = 0
        val normalizedMarker = normalize(marker)
        val knownErrors = setOf("no internet connection", "network error", "couldn't load video",
            "video currently unavailable", "video unavailable", "không có kết nối internet",
            "lỗi mạng", "video không khả dụng", "verify to continue", "xác minh để tiếp tục",
            "log in to tiktok", "đăng nhập vào tiktok")
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || ++visited > 2000 || !node.isVisibleToUser) return
            val bounds = Rect().also(node::getBoundsInScreen)
            val className = node.className?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty().substringAfterLast('/').lowercase(Locale.ROOT)
            val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .map(::normalize).filter(String::isNotBlank)
            if (className.contains("Dialog", true) || className.contains("PopupWindow", true) ||
                className.endsWith("EditText")) blocker = "POPUP_OR_INPUT_SCREEN"
            if (labels.any { it in knownErrors }) blocker = "NETWORK_LOGIN_OR_UNAVAILABLE_SCREEN"
            val compactLeaf = node.childCount == 0 && bounds.height() < height * 0.5 &&
                bounds.width() > 0 && bounds.height() > 0 && bounds.intersects(0, 0, width, height)
            if (compactLeaf && labels.any { it.length <= 1000 && it.contains(normalizedMarker) }) target = true
            if (compactLeaf && className.endsWith("TextView") &&
                id in setOf("caption", "video_desc", "video_description", "content_desc", "description",
                    "desc", "author", "author_name", "user_name", "username", "user_title")) {
                labels.filter { it.length in 3..1000 }.forEach(identities::add)
            }
            val range = node.rangeInfo
            if (range != null && (className.endsWith("SeekBar") || className.endsWith("ProgressBar")) &&
                bounds.width() >= width * 0.4 && bounds.height() <= height * 0.15 &&
                bounds.centerY() > height * 0.5 && bounds.intersects(0, 0, width, height) && range.max > range.min) {
                val progress = (range.current - range.min) / (range.max - range.min)
                if (progress.isFinite() && progress in 0f..1f) ranges.add(progress)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
            }
        }
        visit(root, 0)
        val identity = if (target) "target" else identities.takeIf { it.isNotEmpty() }?.let {
            MessageDigest.getInstance("SHA-256").digest(it.joinToString("\n").toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }
        return ScreenSample(true, identity, target, ranges.singleOrNull(), blocker)
    }
    private fun normalize(text: String) = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
