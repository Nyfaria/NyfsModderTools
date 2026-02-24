package com.nyfaria.nyfsmoddertools.ataw

data class ModProjectInfo(
    val minecraftVersion: String?,
    val loaders: Set<ModLoaderType>,
    val forgeVersion: String? = null,
    val neoforgeVersion: String? = null,
    val fabricLoaderVersion: String? = null
) {
    fun needsObfuscatedNames(): Boolean {
        if (minecraftVersion == null) return false
        val version = parseMinecraftVersion(minecraftVersion)
        val threshold = listOf(1, 20, 5)
        return loaders.contains(ModLoaderType.FORGE) && compareVersions(version, threshold) < 0
    }

    private fun parseMinecraftVersion(version: String): List<Int> {
        return version.split(".").mapNotNull { it.toIntOrNull() }
    }

    private fun compareVersions(v1: List<Int>, v2: List<Int>): Int {
        val maxLen = maxOf(v1.size, v2.size)
        for (i in 0 until maxLen) {
            val a = v1.getOrElse(i) { 0 }
            val b = v2.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }
}

