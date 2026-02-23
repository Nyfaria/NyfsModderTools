package com.nyfaria.nyfsmoddingplugin.ataw

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.util.PsiTreeUtil
import com.nyfaria.nyfsmoddingplugin.settings.NyfsModdingSettings
import java.awt.datatransfer.StringSelection

class CopyATAWAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        if (!NyfsModdingSettings.getInstance().enableCopyATAW) {
            event.presentation.isEnabledAndVisible = false
            return
        }
        val element = getTargetElement(event)
        event.presentation.isEnabledAndVisible = element != null && isMinecraftElement(element)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val element = getTargetElement(event) ?: return
        val projectInfo = ModProjectDetector.detectProjectInfo(project)

        val options = buildOptions(element, projectInfo)
        if (options.isEmpty()) return

        if (options.size == 1) {
            copyToClipboard(options[0].second)
            showNotification(event, "Copied: ${options[0].first}")
            return
        }

        val popup = JBPopupFactory.getInstance().createListPopup(
            object : BaseListPopupStep<Pair<String, String>>("Copy AT/AW", options) {
                override fun getTextFor(value: Pair<String, String>): String = value.first

                override fun onChosen(selectedValue: Pair<String, String>, finalChoice: Boolean): PopupStep<*>? {
                    if (finalChoice) {
                        copyToClipboard(selectedValue.second)
                    }
                    return FINAL_CHOICE
                }
            }
        )

        popup.showInBestPositionFor(event.dataContext)
    }

    private fun getTargetElement(event: AnActionEvent): PsiElement? {
        val psiFile = event.getData(CommonDataKeys.PSI_FILE) ?: return null
        val editor = event.getData(CommonDataKeys.EDITOR)

        if (editor != null) {
            val offset = editor.caretModel.offset
            val element = psiFile.findElementAt(offset)
            return findTargetFromElement(element)
        }

        val psiElement = event.getData(CommonDataKeys.PSI_ELEMENT)
        return findTargetFromElement(psiElement)
    }

    private fun findTargetFromElement(element: PsiElement?): PsiElement? {
        if (element == null) return null

        return when (element) {
            is PsiClass -> element
            is PsiMethod -> element
            is PsiField -> element
            else -> {
                PsiTreeUtil.getParentOfType(element, PsiField::class.java)
                    ?: PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                    ?: PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
            }
        }
    }

    private fun isMinecraftElement(element: PsiElement): Boolean {
        val qualifiedName = when (element) {
            is PsiClass -> element.qualifiedName
            is PsiMethod -> element.containingClass?.qualifiedName
            is PsiField -> element.containingClass?.qualifiedName
            else -> return false
        } ?: return false

        return qualifiedName.startsWith("net.minecraft") ||
               qualifiedName.startsWith("com.mojang")
    }

    private fun buildOptions(element: PsiElement, projectInfo: ModProjectInfo): List<Pair<String, String>> {
        val options = mutableListOf<Pair<String, String>>()

        when (element) {
            is PsiClass -> buildClassOptions(element, projectInfo, options)
            is PsiMethod -> buildMethodOptions(element, projectInfo, options)
            is PsiField -> buildFieldOptions(element, projectInfo, options)
        }

        return options
    }

    private fun buildClassOptions(
        psiClass: PsiClass,
        projectInfo: ModProjectInfo,
        options: MutableList<Pair<String, String>>
    ) {
        val isFinal = psiClass.hasModifierProperty(PsiModifier.FINAL)

        if (projectInfo.loaders.any { it.supportsAccessTransformers() }) {
            options.add("AT (public)" to AccessTransformerGenerator.generateForClass(psiClass, true))
            options.add("AT (protected)" to AccessTransformerGenerator.generateForClass(psiClass, false))
        }

        if (projectInfo.loaders.any { it.supportsAccessWideners() }) {
            options.add("AW (accessible)" to AccessWidenerGenerator.generateForClass(psiClass, AccessWidenerGenerator.AccessType.ACCESSIBLE))
            if (isFinal) {
                options.add("AW (extendable)" to AccessWidenerGenerator.generateForClass(psiClass, AccessWidenerGenerator.AccessType.EXTENDABLE))
            }
        }

        if (options.isEmpty()) {
            options.add("AT" to AccessTransformerGenerator.generateForClass(psiClass, true))
            options.add("AW" to AccessWidenerGenerator.generateForClass(psiClass, AccessWidenerGenerator.AccessType.ACCESSIBLE))
        }
    }

    private fun buildMethodOptions(
        psiMethod: PsiMethod,
        projectInfo: ModProjectInfo,
        options: MutableList<Pair<String, String>>
    ) {
        val isFinal = psiMethod.hasModifierProperty(PsiModifier.FINAL)

        if (projectInfo.loaders.any { it.supportsAccessTransformers() }) {
            options.add("AT (public)" to AccessTransformerGenerator.generateForMethod(psiMethod, true))
            options.add("AT (protected)" to AccessTransformerGenerator.generateForMethod(psiMethod, false))
        }

        if (projectInfo.loaders.any { it.supportsAccessWideners() }) {
            options.add("AW (accessible)" to AccessWidenerGenerator.generateForMethod(psiMethod, AccessWidenerGenerator.AccessType.ACCESSIBLE))
            if (isFinal) {
                options.add("AW (extendable)" to AccessWidenerGenerator.generateForMethod(psiMethod, AccessWidenerGenerator.AccessType.EXTENDABLE))
            }
        }

        if (options.isEmpty()) {
            options.add("AT" to AccessTransformerGenerator.generateForMethod(psiMethod, true))
            options.add("AW" to AccessWidenerGenerator.generateForMethod(psiMethod, AccessWidenerGenerator.AccessType.ACCESSIBLE))
        }
    }

    private fun buildFieldOptions(
        psiField: PsiField,
        projectInfo: ModProjectInfo,
        options: MutableList<Pair<String, String>>
    ) {
        val isFinal = psiField.hasModifierProperty(PsiModifier.FINAL)

        if (projectInfo.loaders.any { it.supportsAccessTransformers() }) {
            options.add("AT (public)" to AccessTransformerGenerator.generateForField(psiField, true, false))
            if (isFinal) {
                options.add("AT (public, remove final)" to AccessTransformerGenerator.generateForField(psiField, true, true))
            }
        }

        if (projectInfo.loaders.any { it.supportsAccessWideners() }) {
            options.add("AW (accessible)" to AccessWidenerGenerator.generateForField(psiField, AccessWidenerGenerator.AccessType.ACCESSIBLE))
            if (isFinal) {
                options.add("AW (mutable)" to AccessWidenerGenerator.generateForField(psiField, AccessWidenerGenerator.AccessType.MUTABLE))
            }
        }

        if (options.isEmpty()) {
            options.add("AT" to AccessTransformerGenerator.generateForField(psiField, true, false))
            options.add("AW" to AccessWidenerGenerator.generateForField(psiField, AccessWidenerGenerator.AccessType.ACCESSIBLE))
        }
    }

    private fun copyToClipboard(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun showNotification(event: AnActionEvent, message: String) {
        val project = event.project ?: return
        com.intellij.openapi.wm.WindowManager.getInstance()
            .getStatusBar(project)
            ?.info = message
    }
}

