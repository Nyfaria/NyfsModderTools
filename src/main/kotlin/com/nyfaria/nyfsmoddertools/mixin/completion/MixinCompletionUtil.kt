package com.nyfaria.nyfsmoddertools.mixin.completion

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

object MixinCompletionUtil {

    private val MIXIN_INJECTION_ANNOTATIONS_FQ = setOf(
        "org.spongepowered.asm.mixin.injection.Inject",
        "org.spongepowered.asm.mixin.injection.Redirect",
        "org.spongepowered.asm.mixin.injection.ModifyArg",
        "org.spongepowered.asm.mixin.injection.ModifyArgs",
        "org.spongepowered.asm.mixin.injection.ModifyConstant",
        "org.spongepowered.asm.mixin.injection.ModifyVariable",
        "org.spongepowered.asm.mixin.Overwrite",
        "com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod",
        "com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation",
        "com.llamalad7.mixinextras.injector.WrapWithCondition",
        "com.llamalad7.mixinextras.injector.ModifyExpressionValue",
        "com.llamalad7.mixinextras.injector.ModifyReturnValue",
        "com.llamalad7.mixinextras.injector.ModifyReceiver",
        "com.llamalad7.mixinextras.injector.v2.WrapWithCondition"
    )

    private val MIXIN_INJECTION_ANNOTATIONS_SHORT = setOf(
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
        "ModifyReceiver"
    )

    private val AT_ANNOTATION_FQ = "org.spongepowered.asm.mixin.injection.At"
    private val AT_ANNOTATION_SHORT = "At"

    private val MIXIN_ANNOTATION_FQ = "org.spongepowered.asm.mixin.Mixin"
    private val MIXIN_ANNOTATION_SHORT = "Mixin"

    fun findMixinTargetClasses(containingClass: PsiClass): List<PsiClass> {
        val mixinAnnotation = containingClass.getAnnotation(MIXIN_ANNOTATION_FQ)
            ?: containingClass.annotations.find { it.qualifiedName?.endsWith(".Mixin") == true || it.qualifiedName == "Mixin" }
            ?: return emptyList()

        val result = mutableListOf<PsiClass>()

        val valueAttr = mixinAnnotation.findAttributeValue("value")
        if (valueAttr != null) {
            extractClassesFromExpression(valueAttr, result)
        }

        if (result.isEmpty()) {
            val targetsAttr = mixinAnnotation.findAttributeValue("targets")
            if (targetsAttr != null) {
                extractClassesFromTargetsString(targetsAttr, containingClass, result)
            }
        }

        return result
    }

    private fun extractClassesFromExpression(expression: PsiElement, result: MutableList<PsiClass>) {
        when (expression) {
            is PsiClassObjectAccessExpression -> {
                val classType = expression.operand.type
                val psiClass = (classType as? com.intellij.psi.PsiClassType)?.resolve()
                if (psiClass != null) {
                    result.add(psiClass)
                }
            }
            else -> {
                for (child in expression.children) {
                    extractClassesFromExpression(child, result)
                }
            }
        }
    }

    private fun extractClassesFromTargetsString(expression: PsiElement, context: PsiClass, result: MutableList<PsiClass>) {
        when (expression) {
            is PsiLiteralExpression -> {
                val value = expression.value as? String ?: return
                val className = value.replace('/', '.')
                val facade = JavaPsiFacade.getInstance(context.project)
                val scope = GlobalSearchScope.allScope(context.project)
                val psiClass = facade.findClass(className, scope)
                if (psiClass != null) {
                    result.add(psiClass)
                }
            }
            else -> {
                for (child in expression.children) {
                    extractClassesFromTargetsString(child, context, result)
                }
            }
        }
    }

    fun isInMethodParameter(annotation: PsiAnnotation): Boolean {
        val qualifiedName = annotation.qualifiedName
        if (qualifiedName != null) {
            if (qualifiedName in MIXIN_INJECTION_ANNOTATIONS_FQ ||
                qualifiedName in MIXIN_INJECTION_ANNOTATIONS_SHORT ||
                MIXIN_INJECTION_ANNOTATIONS_SHORT.any { qualifiedName.endsWith(".$it") }) {
                return true
            }
        }
        val nameElement = annotation.nameReferenceElement
        val referenceName = nameElement?.referenceName
        return referenceName in MIXIN_INJECTION_ANNOTATIONS_SHORT
    }

    fun isAtAnnotation(annotation: PsiAnnotation): Boolean {
        val qualifiedName = annotation.qualifiedName
        if (qualifiedName != null) {
            if (qualifiedName == AT_ANNOTATION_FQ ||
                qualifiedName == AT_ANNOTATION_SHORT ||
                qualifiedName.endsWith(".At")) {
                return true
            }
        }
        val nameElement = annotation.nameReferenceElement
        val referenceName = nameElement?.referenceName
        return referenceName == "At"
    }

    fun getAtAnnotationType(annotation: PsiAnnotation): String? {
        val valueAttr = annotation.findAttributeValue("value")
        return (valueAttr as? PsiLiteralExpression)?.value as? String
    }
}

