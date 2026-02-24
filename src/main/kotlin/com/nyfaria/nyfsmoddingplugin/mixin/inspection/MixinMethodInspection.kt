package com.nyfaria.nyfsmoddingplugin.mixin.inspection

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.nyfaria.nyfsmoddingplugin.mixin.MixinTargetGenerator
import com.nyfaria.nyfsmoddingplugin.mixin.completion.MixinCompletionUtil
import com.nyfaria.nyfsmoddingplugin.settings.NyfsModdingSettings

class MixinMethodInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun getDisplayName(): String = "Invalid Mixin method target"

    override fun getShortName(): String = "MixinMethodInspection"

    override fun getGroupDisplayName(): String = "Mixin"

    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!NyfsModdingSettings.getInstance().enableMixinInspections) {
            return PsiElementVisitor.EMPTY_VISITOR
        }

        return object : JavaElementVisitor() {
            override fun visitAnnotation(annotation: PsiAnnotation) {
                super.visitAnnotation(annotation)

                val refName = annotation.nameReferenceElement?.referenceName
                if (!MixinCompletionUtil.isInMethodParameter(annotation) &&
                    refName !in setOf(
                        "Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyConstant", "ModifyVariable", "Overwrite",
                        "WrapMethod", "WrapOperation", "WrapWithCondition", "ModifyExpressionValue", "ModifyReturnValue", "ModifyReceiver"
                    )) {
                    return
                }

                val containingClass = PsiTreeUtil.getParentOfType(annotation, PsiClass::class.java) ?: return
                val targetClasses = MixinCompletionUtil.findMixinTargetClasses(containingClass)
                if (targetClasses.isEmpty()) return

                val methodAttr = annotation.findAttributeValue("method") ?: return

                when (methodAttr) {
                    is PsiLiteralExpression -> {
                        checkMethodLiteral(methodAttr, targetClasses, holder)
                    }
                    is PsiArrayInitializerMemberValue -> {
                        for (initializer in methodAttr.initializers) {
                            if (initializer is PsiLiteralExpression) {
                                checkMethodLiteral(initializer, targetClasses, holder)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkMethodLiteral(
        literal: PsiLiteralExpression,
        targetClasses: List<PsiClass>,
        holder: ProblemsHolder
    ) {
        val value = literal.value as? String ?: return
        if (value.isEmpty()) return

        val methodName = value.substringBefore("(").substringBefore("*")
        val hasDescriptor = value.contains("(")

        var foundMethod: PsiMethod? = null
        var foundExactMatch = false

        for (targetClass in targetClasses) {
            for (method in targetClass.methods) {
                val actualName = method.name
                if (methodName.endsWith("*")) {
                    if (actualName.startsWith(methodName.dropLast(1))) {
                        foundMethod = method
                        if (!hasDescriptor) {
                            foundExactMatch = true
                            break
                        }
                        val descriptor = MixinTargetGenerator.generateInjectTarget(method)
                            .removePrefix("method = \"").removeSuffix("\"")
                        if (descriptor == value) {
                            foundExactMatch = true
                            break
                        }
                    }
                } else if (actualName == methodName) {
                    foundMethod = method
                    if (!hasDescriptor) {
                        foundExactMatch = true
                        break
                    }
                    val descriptor = MixinTargetGenerator.generateInjectTarget(method)
                        .removePrefix("method = \"").removeSuffix("\"")
                    if (descriptor == value) {
                        foundExactMatch = true
                        break
                    }
                }
            }

            if (methodName == "<init>" || value.startsWith("<init>")) {
                for (constructor in targetClass.constructors) {
                    foundMethod = constructor
                    if (!hasDescriptor) {
                        foundExactMatch = true
                        break
                    }
                    val descriptor = MixinTargetGenerator.generateInjectTarget(constructor)
                        .removePrefix("method = \"").removeSuffix("\"")
                    if (descriptor == value) {
                        foundExactMatch = true
                        break
                    }
                }
            }

            if (foundExactMatch) break
        }

        if (foundMethod == null) {
            val similarMethods = findSimilarMethods(methodName, targetClasses)
            val quickFixes = similarMethods.take(5).map { FixMethodSignatureQuickFix(it) }.toTypedArray()

            holder.registerProblem(
                literal,
                "Cannot find method '$methodName' in target class",
                ProblemHighlightType.ERROR,
                *quickFixes
            )
        } else if (!foundExactMatch) {
            val correctDescriptor = MixinTargetGenerator.generateInjectTarget(foundMethod)
                .removePrefix("method = \"").removeSuffix("\"")
            holder.registerProblem(
                literal,
                "Method signature mismatch. Expected: $correctDescriptor",
                ProblemHighlightType.ERROR,
                FixMethodSignatureQuickFix(correctDescriptor)
            )
        }
    }

    private fun findSimilarMethods(methodName: String, targetClasses: List<PsiClass>): List<String> {
        val results = mutableListOf<String>()
        for (targetClass in targetClasses) {
            for (method in targetClass.methods) {
                if (method.name.contains(methodName, ignoreCase = true) ||
                    methodName.contains(method.name, ignoreCase = true) ||
                    levenshteinDistance(method.name, methodName) <= 3) {
                    val descriptor = MixinTargetGenerator.generateInjectTarget(method)
                        .removePrefix("method = \"").removeSuffix("\"")
                    results.add(descriptor)
                }
            }
        }
        return results.distinct()
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[m][n]
    }

    private class FixMethodSignatureQuickFix(private val correctSignature: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Fix method signature"

        override fun getName(): String = "Change to '$correctSignature'"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val literal = descriptor.psiElement as? PsiLiteralExpression ?: return
            val factory = com.intellij.psi.JavaPsiFacade.getElementFactory(project)
            val newLiteral = factory.createExpressionFromText("\"$correctSignature\"", literal)
            literal.replace(newLiteral)
        }
    }
}

