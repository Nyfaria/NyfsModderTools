package com.nyfaria.nyfsmoddertools.ataw

import com.intellij.openapi.project.Project
import java.util.Properties

object ModProjectDetector {

    fun detectProjectInfo(project: Project): ModProjectInfo {
        val baseDir = project.basePath?.let { java.io.File(it) } ?: return defaultInfo()

        val gradleProperties = findGradleProperties(baseDir)
        if (gradleProperties == null) return defaultInfo()

        val props = Properties()
        gradleProperties.inputStream().use { props.load(it) }

        val mcVersion = props.getProperty("minecraft_version")
        val forgeVersion = props.getProperty("forge_version")
        val neoforgeVersion = props.getProperty("neoforge_version")
        val fabricLoaderVersion = props.getProperty("fabric_loader_version")

        val loaders = mutableSetOf<ModLoaderType>()

        if (hasSubproject(baseDir, "forge") || forgeVersion != null) {
            loaders.add(ModLoaderType.FORGE)
        }
        if (hasSubproject(baseDir, "neoforge") || neoforgeVersion != null) {
            loaders.add(ModLoaderType.NEOFORGE)
        }
        if (hasSubproject(baseDir, "fabric") || fabricLoaderVersion != null) {
            loaders.add(ModLoaderType.FABRIC)
        }

        if (loaders.isEmpty()) {
            loaders.addAll(detectLoadersFromBuildFiles(baseDir))
        }

        return ModProjectInfo(
            minecraftVersion = mcVersion,
            loaders = loaders,
            forgeVersion = forgeVersion,
            neoforgeVersion = neoforgeVersion,
            fabricLoaderVersion = fabricLoaderVersion
        )
    }

    private fun findGradleProperties(baseDir: java.io.File): java.io.File? {
        val gradleProps = java.io.File(baseDir, "gradle.properties")
        return if (gradleProps.exists()) gradleProps else null
    }

    private fun hasSubproject(baseDir: java.io.File, name: String): Boolean {
        return java.io.File(baseDir, name).isDirectory
    }

    private fun detectLoadersFromBuildFiles(baseDir: java.io.File): Set<ModLoaderType> {
        val loaders = mutableSetOf<ModLoaderType>()
        val buildFiles = listOf(
            java.io.File(baseDir, "build.gradle"),
            java.io.File(baseDir, "build.gradle.kts"),
            java.io.File(baseDir, "settings.gradle"),
            java.io.File(baseDir, "settings.gradle.kts")
        )

        for (buildFile in buildFiles) {
            if (!buildFile.exists()) continue
            val content = buildFile.readText()

            if (content.contains("net.minecraftforge") || content.contains("forge")) {
                loaders.add(ModLoaderType.FORGE)
            }
            if (content.contains("net.neoforged") || content.contains("neoforge")) {
                loaders.add(ModLoaderType.NEOFORGE)
            }
            if (content.contains("fabric-loom") || content.contains("fabricmc")) {
                loaders.add(ModLoaderType.FABRIC)
            }
        }

        return loaders
    }

    private fun defaultInfo() = ModProjectInfo(
        minecraftVersion = null,
        loaders = setOf(ModLoaderType.UNKNOWN)
    )
}

