package com.nyfaria.nyfsmoddertools.wizard

import com.intellij.ide.util.projectWizard.ModuleBuilder
import com.intellij.ide.util.projectWizard.ModuleWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.module.StdModuleTypes
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.vfs.LocalFileSystem
import com.nyfaria.nyfsmoddertools.settings.NyfsModdingSettings
import java.io.File
import javax.swing.Icon

class MinecraftModuleBuilder : ModuleBuilder() {

    private val log = Logger.getInstance(MinecraftModuleBuilder::class.java)

    var modName: String = ""
    var modId: String = ""
    var group: String = "com.example"
    var minecraftVersion: MinecraftVersion = MinecraftVersion.V1_21_1
    var initGit: Boolean = true

    override fun isAvailable(): Boolean = NyfsModdingSettings.getInstance().enableProjectTemplate

    override fun getModuleType(): ModuleType<*> = StdModuleTypes.JAVA

    override fun getName(): String = "Minecraft Mod"

    override fun getPresentableName(): String = "Minecraft Mod"

    override fun getDescription(): String = "Create a new Minecraft mod project using NyfsMultiLoader Template"

    override fun getNodeIcon(): Icon? = null

    override fun setupRootModel(modifiableRootModel: ModifiableRootModel) {
        val project = modifiableRootModel.project
        val contentEntryPath = contentEntryPath ?: return
        val contentRoot = File(contentEntryPath)

        if (!contentRoot.exists()) {
            contentRoot.mkdirs()
        }

        val shouldInitGit = initGit

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Creating Minecraft mod project", false) {
            override fun run(indicator: ProgressIndicator) {
                val success = TemplateDownloader.downloadAndExtract(
                    version = minecraftVersion,
                    targetDir = contentRoot,
                    modName = modName,
                    modId = modId,
                    group = group,
                    indicator = indicator
                )

                if (success) {
                    if (shouldInitGit) {
                        indicator.text = "Initializing Git repository..."
                        indicator.fraction = 0.9
                        initGitRepository(contentRoot)
                    }

                    ApplicationManager.getApplication().invokeLater {
                        LocalFileSystem.getInstance().refresh(true)
                        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(contentRoot)
                        virtualFile?.refresh(false, true)
                    }
                }
            }
        })

        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(contentRoot)
        if (virtualFile != null) {
            modifiableRootModel.addContentEntry(virtualFile)
        }
    }

    override fun getCustomOptionsStep(context: WizardContext, parentDisposable: Disposable): ModuleWizardStep {
        return MinecraftModWizardStep(context, this)
    }

    private fun initGitRepository(projectDir: File) {
        try {
            log.info("Initializing git in: ${projectDir.absolutePath}")
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val gitCommand = if (isWindows) "git.exe" else "git"

            val initProcess = ProcessBuilder(gitCommand, "init")
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
            val initResult = initProcess.waitFor()
            val initOutput = initProcess.inputStream.bufferedReader().readText()
            log.info("git init result: $initResult, output: $initOutput")
        } catch (e: Exception) {
            log.error("Failed to initialize git", e)
        }
    }
}



