package com.nyfaria.nyfsmoddingplugin.mixin.inspection

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.nyfaria.nyfsmoddingplugin.settings.NyfsModdingSettings
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.JsonArray
import com.google.gson.JsonObject as GsonJsonObject

class MixinNotRegisteredInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun getDisplayName(): String = "Mixin class not registered in mixins.json"

    override fun getShortName(): String = "MixinNotRegisteredInspection"

    override fun getGroupDisplayName(): String = "Mixin"

    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!NyfsModdingSettings.getInstance().enableMixinInspections) {
            return PsiElementVisitor.EMPTY_VISITOR
        }

        return object : JavaElementVisitor() {
            override fun visitClass(aClass: PsiClass) {
                super.visitClass(aClass)

                if (!isMixinClass(aClass)) return

                val qualifiedName = aClass.qualifiedName ?: return
                val project = aClass.project

                val registeredMixins = findAllRegisteredMixins(project)

                if (qualifiedName !in registeredMixins) {
                    val mixinJsonFiles = findMixinJsonFiles(project)
                    val quickFixes = if (mixinJsonFiles.isNotEmpty()) {
                        arrayOf(AddToMixinsJsonQuickFix(qualifiedName, mixinJsonFiles))
                    } else {
                        emptyArray()
                    }

                    holder.registerProblem(
                        aClass.nameIdentifier ?: aClass,
                        "Mixin class '$qualifiedName' is not registered in any mixins.json file",
                        ProblemHighlightType.WARNING,
                        *quickFixes
                    )
                }
            }
        }
    }

    private fun isMixinClass(psiClass: PsiClass): Boolean {
        return psiClass.annotations.any { annotation ->
            val qualifiedName = annotation.qualifiedName
            qualifiedName == "org.spongepowered.asm.mixin.Mixin" ||
            qualifiedName?.endsWith(".Mixin") == true ||
            annotation.nameReferenceElement?.referenceName == "Mixin"
        }
    }

    private class AddToMixinsJsonQuickFix(
        private val mixinClassName: String,
        private val mixinJsonFiles: List<VirtualFile>
    ) : LocalQuickFix {

        override fun getFamilyName(): String = "Add to mixins.json"

        override fun getName(): String = "Add '$mixinClassName' to mixins.json"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            if (mixinJsonFiles.isEmpty()) return

            if (mixinJsonFiles.size == 1) {
                addToMixinsJson(project, mixinJsonFiles[0], mixinClassName)
            } else {
                showFileSelectionPopup(project, mixinJsonFiles, mixinClassName)
            }
        }

        private fun showFileSelectionPopup(project: Project, files: List<VirtualFile>, className: String) {
            val step = object : BaseListPopupStep<VirtualFile>("Select mixins.json file", files) {
                override fun getTextFor(value: VirtualFile): String {
                    return extractModuleName(value.path) ?: value.name
                }

                override fun onChosen(selectedValue: VirtualFile, finalChoice: Boolean): PopupStep<*>? {
                    if (finalChoice) {
                        showArraySelectionPopup(project, selectedValue, className)
                    }
                    return FINAL_CHOICE
                }
            }

            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor != null) {
                JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(editor)
            } else {
                JBPopupFactory.getInstance().createListPopup(step).showInFocusCenter()
            }
        }

        private fun extractModuleName(path: String): String? {
            val normalizedPath = path.replace("\\", "/")
            val modulePatterns = listOf("common", "fabric", "neoforge", "forge", "quilt")
            for (module in modulePatterns) {
                if (normalizedPath.contains("/$module/")) {
                    return module
                }
            }
            return null
        }

        private fun showArraySelectionPopup(project: Project, file: VirtualFile, className: String) {
            val arrays = listOf("mixins", "client", "server")
            val step = object : BaseListPopupStep<String>("Add to which array?", arrays) {
                override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                    if (finalChoice) {
                        addToMixinsJson(project, file, className, selectedValue)
                    }
                    return FINAL_CHOICE
                }
            }

            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor != null) {
                JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(editor)
            } else {
                JBPopupFactory.getInstance().createListPopup(step).showInFocusCenter()
            }
        }

        companion object {
            fun addToMixinsJson(project: Project, file: VirtualFile, className: String, arrayName: String = "mixins") {
                WriteAction.run<Exception> {
                    try {
                        val content = String(file.contentsToByteArray(), Charsets.UTF_8)
                        val jsonObject = JsonParser.parseString(content).asJsonObject

                        val packageName = jsonObject.get("package")?.asString ?: ""

                        val relativeName = if (packageName.isNotEmpty() && className.startsWith("$packageName.")) {
                            className.removePrefix("$packageName.")
                        } else {
                            className
                        }

                        var targetArray = jsonObject.getAsJsonArray(arrayName)
                        if (targetArray == null) {
                            targetArray = JsonArray()
                            jsonObject.add(arrayName, targetArray)
                        }

                        val alreadyExists = targetArray.any { it.asString == relativeName }
                        if (!alreadyExists) {
                            targetArray.add(relativeName)

                            val gson = GsonBuilder().setPrettyPrinting().create()
                            val newContent = gson.toJson(jsonObject)
                            file.setBinaryContent(newContent.toByteArray(Charsets.UTF_8))
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    companion object {
        fun findAllRegisteredMixins(project: Project): Set<String> {
            val registeredMixins = mutableSetOf<String>()

            val mixinJsonFiles = findMixinJsonFiles(project)

            for (file in mixinJsonFiles) {
                try {
                    val content = String(file.contentsToByteArray(), Charsets.UTF_8)
                    val jsonObject = JsonParser.parseString(content).asJsonObject

                    val packageName = jsonObject.get("package")?.asString ?: ""

                    for (propertyName in listOf("mixins", "client", "server")) {
                        val mixinsArray = jsonObject.getAsJsonArray(propertyName) ?: continue
                        for (element in mixinsArray) {
                            val mixinName = element.asString ?: continue
                            val fullName = if (packageName.isNotEmpty()) "$packageName.$mixinName" else mixinName
                            registeredMixins.add(fullName)
                        }
                    }
                } catch (e: Exception) {
                }
            }

            return registeredMixins
        }

        fun findMixinJsonFiles(project: Project): List<VirtualFile> {
            return FilenameIndex.getAllFilesByExt(project, "json", GlobalSearchScope.projectScope(project))
                .filter { it.name.endsWith(".mixins.json") || it.name.endsWith("-mixin.json") || it.name == "mixins.json" }
        }
    }
}

