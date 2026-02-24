package com.nyfaria.nyfsmoddertools.wizard

enum class MinecraftVersion(val displayName: String, val branch: String) {
    V1_20_1("1.20.1", "LegacyEra"),
    V1_21_1("1.21.1", "NeoObfEra"),
    V1_21_3("1.21.3", "NeoObfEra"),
    V1_21_4("1.21.4", "NeoObfEra"),
    V1_21_5("1.21.5", "NeoObfEra"),
    V1_21_6("1.21.6", "NeoObfEra"),
    V1_21_7("1.21.7", "NeoObfEra"),
    V1_21_8("1.21.8", "NeoObfEra"),
    V1_21_9("1.21.9", "NeoObfEra"),
    V1_21_10("1.21.10", "NeoObfEra"),
    V1_21_11("1.21.11", "NeoObfEra");

    override fun toString(): String = displayName

    companion object {
        fun fromDisplayName(name: String): MinecraftVersion? {
            return entries.find { it.displayName == name }
        }
    }
}

