package com.nyfaria.nyfsmoddertools.mixin

import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.nyfaria.nyfsmoddertools.mixin.inspection.MixinNotRegisteredInspection

class MixinImplicitUsageProvider : ImplicitUsageProvider {

    private val MIXIN_METHOD_ANNOTATIONS = setOf(
        "Inject",
        "Redirect",
        "ModifyArg",
        "ModifyArgs",
        "ModifyConstant",
        "ModifyVariable",
        "Overwrite",
        "WrapMethod",
        "WrapOperation",
        "WrapWithCondition",
        "ModifyExpressionValue",
        "ModifyReturnValue",
        "ModifyReceiver",
        "Accessor",
        "Invoker",
        "Shadow"
    )

    private val MIXIN_FIELD_ANNOTATIONS = setOf(
        "Shadow",
        "Final",
        "Mutable",
        "Unique"
    )

    override fun isImplicitUsage(element: PsiElement): Boolean {
        return when (element) {
            is PsiClass -> isUsedMixinClass(element)
            is PsiMethod -> isUsedMixinMethod(element)
            is PsiField -> isUsedMixinField(element)
            else -> false
        }
    }

    override fun isImplicitRead(element: PsiElement): Boolean {
        return false
    }

    override fun isImplicitWrite(element: PsiElement): Boolean {
        return false
    }

    private fun isUsedMixinClass(psiClass: PsiClass): Boolean {
        if (!isMixinClass(psiClass)) return false

        val qualifiedName = psiClass.qualifiedName ?: return false
        val project = psiClass.project

        val registeredMixins = MixinNotRegisteredInspection.findAllRegisteredMixins(project)
        return qualifiedName in registeredMixins
    }

    private fun isUsedMixinMethod(method: PsiMethod): Boolean {
        val containingClass = method.containingClass ?: return false
        if (!isMixinClass(containingClass)) return false

        return method.annotations.any { annotation ->
            val refName = annotation.nameReferenceElement?.referenceName
            refName in MIXIN_METHOD_ANNOTATIONS
        }
    }

    private fun isUsedMixinField(field: PsiField): Boolean {
        val containingClass = field.containingClass ?: return false
        if (!isMixinClass(containingClass)) return false

        return field.annotations.any { annotation ->
            val refName = annotation.nameReferenceElement?.referenceName
            refName in MIXIN_FIELD_ANNOTATIONS
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
}

