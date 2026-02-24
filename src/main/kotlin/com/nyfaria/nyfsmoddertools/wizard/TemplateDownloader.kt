package com.nyfaria.nyfsmoddertools.wizard

import com.intellij.openapi.progress.ProgressIndicator
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream

object TemplateDownloader {

    private const val REPO_OWNER = "Nyfaria"
    private const val REPO_NAME = "NyfsMultiLoader-Template"

    fun downloadAndExtract(
        version: MinecraftVersion,
        targetDir: File,
        modName: String,
        modId: String,
        group: String,
        indicator: ProgressIndicator
    ): Boolean {
        indicator.text = "Downloading template..."
        indicator.fraction = 0.0

        val branch = version.branch
        val zipUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/archive/refs/heads/$branch.zip"

        val tempZip = File.createTempFile("mc-template-", ".zip")
        try {
            downloadFile(zipUrl, tempZip, indicator)

            indicator.text = "Extracting template..."
            indicator.fraction = 0.5

            extractZip(tempZip, targetDir, indicator)

            indicator.text = "Configuring project..."
            indicator.fraction = 0.8

            configureProject(targetDir, version, modName, modId, group)

            indicator.fraction = 1.0
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            tempZip.delete()
        }
    }

    private fun downloadFile(urlString: String, targetFile: File, indicator: ProgressIndicator) {
        val url = URI(urlString).toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connect()

        if (connection.responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
            connection.responseCode == HttpURLConnection.HTTP_MOVED_PERM) {
            val newUrl = connection.getHeaderField("Location")
            connection.disconnect()
            downloadFile(newUrl, targetFile, indicator)
            return
        }

        val totalSize = connection.contentLengthLong
        var downloadedSize = 0L

        connection.inputStream.use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedSize += bytesRead
                    if (totalSize > 0) {
                        indicator.fraction = (downloadedSize.toDouble() / totalSize) * 0.5
                    }
                }
            }
        }
        connection.disconnect()
    }

    private fun extractZip(zipFile: File, targetDir: File, indicator: ProgressIndicator) {
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            var rootFolder: String? = null

            while (entry != null) {
                if (rootFolder == null && entry.isDirectory) {
                    rootFolder = entry.name
                }

                val entryName = if (rootFolder != null && entry.name.startsWith(rootFolder)) {
                    entry.name.substring(rootFolder.length)
                } else {
                    entry.name
                }

                if (entryName.isNotEmpty()) {
                    val targetFile = File(targetDir, entryName)

                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { output ->
                            zis.copyTo(output)
                        }
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun configureProject(
        projectDir: File,
        version: MinecraftVersion,
        modName: String,
        modId: String,
        group: String
    ) {
        val gradleProperties = File(projectDir, "gradle.properties")
        if (gradleProperties.exists()) {
            var content = gradleProperties.readText()

            content = content.replace(Regex("(?m)^mod_name=.*$"), "mod_name=$modName")
            content = content.replace(Regex("(?m)^mod_id=.*$"), "mod_id=$modId")
            content = content.replace(Regex("(?m)^group=.*$"), "group=$group")
            content = content.replace(Regex("(?m)^minecraft_version=.*$"), "minecraft_version=${version.displayName}")

            gradleProperties.writeText(content)
        }
    }
}



