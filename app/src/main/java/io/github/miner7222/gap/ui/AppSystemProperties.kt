package io.github.miner7222.gap.ui

internal object AppSystemProperties {

    fun get(key: String, defaultValue: String = ""): String {
        runCatching {
            val process = ProcessBuilder("getprop", key)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotBlank()) {
                return output
            }
        }

        return runCatching {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val method = systemProperties.getDeclaredMethod(
                "get",
                String::class.java,
                String::class.java,
            )
            method.isAccessible = true
            method.invoke(null, key, defaultValue) as? String ?: defaultValue
        }.getOrDefault(defaultValue)
    }
}
