package io.github.miner7222.gap.ui

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val body: String,
)

object UpdateChecker {

    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000
    private const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/miner7222/GAP/releases/latest"
    const val RELEASES_URL = "https://github.com/miner7222/GAP/releases"

    fun fetchLatestRelease(): ReleaseInfo? {
        val connection = (URL(LATEST_RELEASE_API_URL).openConnection() as? HttpURLConnection) ?: return null

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "GAP/${System.getProperty("http.agent").orEmpty()}")

            if (connection.responseCode !in 200..299) {
                return null
            }

            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(payload)
            ReleaseInfo(
                tagName = json.optString("tag_name").trim(),
                body = json.optString("body").trim(),
            )
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    fun isNewerRelease(latestTag: String, currentVersionName: String): Boolean {
        val latest = extractVersionNumbers(latestTag)
        val current = extractVersionNumbers(currentVersionName)
        if (latest.isEmpty() || current.isEmpty()) {
            return false
        }

        val maxSize = maxOf(latest.size, current.size)
        for (index in 0 until maxSize) {
            val latestPart = latest.getOrElse(index) { 0 }
            val currentPart = current.getOrElse(index) { 0 }
            if (latestPart != currentPart) {
                return latestPart > currentPart
            }
        }

        return false
    }

    private fun extractVersionNumbers(versionName: String): List<Int> {
        return Regex("\\d+")
            .findAll(versionName)
            .mapNotNull { match -> match.value.toIntOrNull() }
            .toList()
    }
}
