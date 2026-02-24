package com.nyfaria.nyfsmoddertools.mixin

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import com.nyfaria.nyfsmoddertools.settings.NyfsModdingSettings
import java.awt.datatransfer.StringSelection

data class TargetContext(
    val element: PsiElement,
    val callSiteClass: PsiClass? = null
)

class CopyMixinTargetAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        if (!NyfsModdingSettings.getInstance().enableCopyMixinTarget) {
            event.presentation.isEnabledAndVisible = false
            return
        }
        val context = getTargetContext(event)
        event.presentation.isEnabledAndVisible = context != null && isMinecraftElement(context.element)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val context = getTargetContext(event) ?: return

        val options = buildOptions(context)
        if (options.isEmpty()) return

        if (options.size == 1) {
            copyToClipboard(options[0].second)
            showNotification(event, "Copied: ${options[0].first}")
            return
        }

        val popup = JBPopupFactory.getInstance().createListPopup(
            object : BaseListPopupStep<Pair<String, String>>("Copy Mixin Target", options) {
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

    private fun getTargetContext(event: AnActionEvent): TargetContext? {
        val psiFile = event.getData(CommonDataKeys.PSI_FILE) ?: return null
        val editor = event.getData(CommonDataKeys.EDITOR)

        if (editor != null) {
            val offset = editor.caretModel.offset
            val element = psiFile.findElementAt(offset)
            return findTargetContext(element)
        }

        val psiElement = event.getData(CommonDataKeys.PSI_ELEMENT)
        return findTargetContext(psiElement)
    }

    private fun findTargetContext(element: PsiElement?): TargetContext? {
        if (element == null) return null

        if (element is PsiClass) return TargetContext(element)
        if (element is PsiMethod) return TargetContext(element)
        if (element is PsiField) return TargetContext(element)

        val methodCall = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java, false)
        if (methodCall != null) {
            val methodRef = methodCall.methodExpression
            val resolved = methodRef.resolve()
            if (resolved is PsiMethod) {
                val qualifierExpr = methodRef.qualifierExpression
                val callSiteClass = if (qualifierExpr != null) {
                    val qualifierType = qualifierExpr.type
                    if (qualifierType is PsiClassType) {
                        qualifierType.resolve()
                    } else null
                } else null
                return TargetContext(resolved, callSiteClass)
            }
        }

        val refExpr = PsiTreeUtil.getParentOfType(element, PsiReferenceExpression::class.java, false)
        if (refExpr != null) {
            val resolved = refExpr.resolve()
            when (resolved) {
                is PsiMethod -> {
                    val qualifierExpr = refExpr.qualifierExpression
                    val callSiteClass = if (qualifierExpr != null) {
                        val qualifierType = qualifierExpr.type
                        if (qualifierType is PsiClassType) {
                            qualifierType.resolve()
                        } else null
                    } else null
                    return TargetContext(resolved, callSiteClass)
                }
                is PsiField -> {
                    val qualifierExpr = refExpr.qualifierExpression
                    val callSiteClass = if (qualifierExpr != null) {
                        val qualifierType = qualifierExpr.type
                        if (qualifierType is PsiClassType) {
                            qualifierType.resolve()
                        } else null
                    } else null
                    return TargetContext(resolved, callSiteClass)
                }
            }
        }

        val field = PsiTreeUtil.getParentOfType(element, PsiField::class.java)
        if (field != null) return TargetContext(field)

        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        if (method != null) return TargetContext(method)

        val clazz = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        if (clazz != null) return TargetContext(clazz)

        return null
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

    private fun buildOptions(context: TargetContext): List<Pair<String, String>> {
        val options = mutableListOf<Pair<String, String>>()

        when (val element = context.element) {
            is PsiClass -> buildClassOptions(element, options)
            is PsiMethod -> buildMethodOptions(element, context.callSiteClass, options)
            is PsiField -> buildFieldOptions(element, context.callSiteClass, options)
        }

        return options
    }

    private fun buildClassOptions(
        psiClass: PsiClass,
        options: MutableList<Pair<String, String>>
    ) {
        options.add("@Mixin annotation" to MixinTargetGenerator.generateMixinAnnotation(psiClass))
    }

    private fun buildMethodOptions(
        psiMethod: PsiMethod,
        callSiteClass: PsiClass?,
        options: MutableList<Pair<String, String>>
    ) {
        val isPrivate = psiMethod.hasModifierProperty(PsiModifier.PRIVATE)

        options.add("@Inject method target" to MixinTargetGenerator.generateInjectTarget(psiMethod))
        options.add("@At INVOKE target" to MixinTargetGenerator.generateAtInvokeTarget(psiMethod, callSiteClass))
        options.add("@Redirect target" to MixinTargetGenerator.generateRedirectTarget(psiMethod, callSiteClass))

        if (isPrivate) {
            options.add("@Invoker annotation" to MixinTargetGenerator.generateInvokerAnnotation(psiMethod))
        }

        options.add("@Shadow method" to MixinTargetGenerator.generateShadowMethod(psiMethod))
    }

    private fun buildFieldOptions(
        psiField: PsiField,
        callSiteClass: PsiClass?,
        options: MutableList<Pair<String, String>>
    ) {
        val isPrivate = psiField.hasModifierProperty(PsiModifier.PRIVATE)
        val isStatic = psiField.hasModifierProperty(PsiModifier.STATIC)

        options.add("Field target" to MixinTargetGenerator.generateFieldTarget(psiField, callSiteClass))

        if (isStatic) {
            options.add("@At FIELD (GETSTATIC)" to MixinTargetGenerator.generateAtFieldTarget(psiField, MixinTargetGenerator.FieldOpcode.GETSTATIC, callSiteClass))
            options.add("@At FIELD (PUTSTATIC)" to MixinTargetGenerator.generateAtFieldTarget(psiField, MixinTargetGenerator.FieldOpcode.PUTSTATIC, callSiteClass))
        } else {
            options.add("@At FIELD (GETFIELD)" to MixinTargetGenerator.generateAtFieldTarget(psiField, MixinTargetGenerator.FieldOpcode.GETFIELD, callSiteClass))
            options.add("@At FIELD (PUTFIELD)" to MixinTargetGenerator.generateAtFieldTarget(psiField, MixinTargetGenerator.FieldOpcode.PUTFIELD, callSiteClass))
        }

        if (isPrivate) {
            options.add("@Accessor annotation" to MixinTargetGenerator.generateAccessorAnnotation(psiField))
        }

        options.add("@Shadow field" to MixinTargetGenerator.generateShadowField(psiField))
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

