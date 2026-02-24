package com.nyfaria.nyfsmoddertools.ataw

enum class ModLoaderType {
    FORGE,
    NEOFORGE,
    FABRIC,
    UNKNOWN;

    fun usesObfuscatedNames(): Boolean = this == FORGE

    fun supportsAccessTransformers(): Boolean = this == FORGE || this == NEOFORGE

    fun supportsAccessWideners(): Boolean = this == FABRIC
}

