package com.nyfaria.nyfsmoddingplugin.mixin.inspection

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.nyfaria.nyfsmoddingplugin.mixin.MixinTargetGenerator
import com.nyfaria.nyfsmoddingplugin.mixin.completion.MixinCompletionUtil
import com.nyfaria.nyfsmoddingplugin.settings.NyfsModdingSettings

class MixinAtTargetInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun getDisplayName(): String = "Invalid Mixin @At target"

    override fun getShortName(): String = "MixinAtTargetInspection"

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
                if (!MixinCompletionUtil.isAtAnnotation(annotation) && refName != "At") {
                    return
                }

                val targetAttr = annotation.findAttributeValue("target")
                if (targetAttr !is PsiLiteralExpression) return

                val targetValue = targetAttr.value as? String ?: return
                if (targetValue.isEmpty()) return

                val atType = MixinCompletionUtil.getAtAnnotationType(annotation)

                val parentAnnotation = findParentInjectionAnnotation(annotation) ?: return
                val containingClass = PsiTreeUtil.getParentOfType(parentAnnotation, PsiClass::class.java) ?: return
                val targetClasses = MixinCompletionUtil.findMixinTargetClasses(containingClass)
                if (targetClasses.isEmpty()) return

                val methodDescriptors = getMethodDescriptorsFromAnnotation(parentAnnotation)
                if (methodDescriptors.isEmpty()) return

                val targetMethods = findTargetMethods(targetClasses, methodDescriptors)
                if (targetMethods.isEmpty()) return

                val validTargets = mutableSetOf<String>()

                for (method in targetMethods) {
                    when (atType) {
                        "INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING" -> {
                            collectMethodTargets(method, validTargets)
                        }
                        "FIELD" -> {
                            collectFieldTargets(method, validTargets)
                        }
                        "NEW" -> {
                            collectConstructorTargets(method, validTargets)
                        }
                        else -> {
                            collectMethodTargets(method, validTargets)
                            collectFieldTargets(method, validTargets)
                        }
                    }
                }

                if (targetValue !in validTargets) {
                    val targetType = when (atType) {
                        "INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING" -> "method call"
                        "FIELD" -> "field access"
                        "NEW" -> "constructor call"
                        else -> "target"
                    }
                    holder.registerProblem(
                        targetAttr,
                        "Cannot find $targetType '$targetValue' in the specified method(s)",
                        ProblemHighlightType.ERROR
                    )
                }
            }
        }
    }

    private fun findParentInjectionAnnotation(atAnnotation: PsiAnnotation): PsiAnnotation? {
        var element = atAnnotation.parent
        while (element != null && element !is PsiClass) {
            if (element is PsiAnnotation) {
                val refName = element.nameReferenceElement?.referenceName
                if (MixinCompletionUtil.isInMethodParameter(element) ||
                    refName in MIXIN_INJECTION_ANNOTATION_NAMES) {
                    return element
                }
            }
            if (element is PsiNameValuePair) {
                val parentAnnotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java)
                if (parentAnnotation != null) {
                    val refName = parentAnnotation.nameReferenceElement?.referenceName
                    if (MixinCompletionUtil.isInMethodParameter(parentAnnotation) ||
                        refName in MIXIN_INJECTION_ANNOTATION_NAMES) {
                        return parentAnnotation
                    }
                }
            }
            element = element.parent
        }
        return null
    }

    companion object {
        private val MIXIN_INJECTION_ANNOTATION_NAMES = setOf(
            "Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyConstant", "ModifyVariable", "Overwrite",
            "WrapMethod", "WrapOperation", "WrapWithCondition", "ModifyExpressionValue", "ModifyReturnValue", "ModifyReceiver"
        )
    }

    private fun getMethodDescriptorsFromAnnotation(annotation: PsiAnnotation): List<String> {
        val result = mutableListOf<String>()
        val methodAttr = annotation.findAttributeValue("method") ?: return result

        when (methodAttr) {
            is PsiLiteralExpression -> {
                val value = methodAttr.value as? String
                if (value != null) result.add(value)
            }
            is PsiArrayInitializerMemberValue -> {
                for (initializer in methodAttr.initializers) {
                    if (initializer is PsiLiteralExpression) {
                        val value = initializer.value as? String
                        if (value != null) result.add(value)
                    }
                }
            }
        }
        return result
    }

    private fun findTargetMethods(targetClasses: List<PsiClass>, methodDescriptors: List<String>): List<PsiMethod> {
        val result = mutableListOf<PsiMethod>()

        for (targetClass in targetClasses) {
            for (descriptor in methodDescriptors) {
                val methodName = descriptor.substringBefore("(").substringBefore("*")

                for (method in targetClass.methods) {
                    if (matchesMethodDescriptor(method, descriptor, methodName)) {
                        result.add(method)
                    }
                }

                if (methodName == "<init>" || descriptor.startsWith("<init>")) {
                    for (constructor in targetClass.constructors) {
                        if (matchesMethodDescriptor(constructor, descriptor, "<init>")) {
                            result.add(constructor)
                        }
                    }
                }
            }
        }
        return result
    }

    private fun matchesMethodDescriptor(method: PsiMethod, descriptor: String, methodName: String): Boolean {
        val actualName = if (method.isConstructor) "<init>" else method.name

        if (methodName.endsWith("*")) {
            val prefix = methodName.dropLast(1)
            if (!actualName.startsWith(prefix)) return false
        } else if (actualName != methodName) {
            return false
        }

        if (!descriptor.contains("(")) return true

        val expectedDescriptor = MixinTargetGenerator.generateInjectTarget(method)
            .removePrefix("method = \"").removeSuffix("\"")

        return expectedDescriptor == descriptor
    }

    private fun collectMethodTargets(method: PsiMethod, targets: MutableSet<String>) {
        val sourceMethod = method.navigationElement as? PsiMethod ?: method
        val body = sourceMethod.body ?: method.body ?: return

        body.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                val resolvedMethod = expression.resolveMethod() ?: return
                val qualifierType = expression.methodExpression.qualifierExpression?.type
                val callSiteClass = (qualifierType as? PsiClassType)?.resolve()
                val target = MixinTargetGenerator.generateRedirectTarget(resolvedMethod, callSiteClass)
                targets.add(target)
            }
        })
    }

    private fun collectFieldTargets(method: PsiMethod, targets: MutableSet<String>) {
        val sourceMethod = method.navigationElement as? PsiMethod ?: method
        val body = sourceMethod.body ?: method.body ?: return

        body.accept(object : JavaRecursiveElementVisitor() {
            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                super.visitReferenceExpression(expression)
                val resolved = expression.resolve()
                if (resolved is PsiField) {
                    val qualifierType = expression.qualifierExpression?.type
                    val callSiteClass = (qualifierType as? PsiClassType)?.resolve()
                    val target = MixinTargetGenerator.generateFieldTarget(resolved, callSiteClass)
                    targets.add(target)
                }
            }
        })
    }

    private fun collectConstructorTargets(method: PsiMethod, targets: MutableSet<String>) {
        val sourceMethod = method.navigationElement as? PsiMethod ?: method
        val body = sourceMethod.body ?: method.body ?: return

        body.accept(object : JavaRecursiveElementVisitor() {
            override fun visitNewExpression(expression: PsiNewExpression) {
                super.visitNewExpression(expression)
                val constructor = expression.resolveConstructor() ?: return
                val classRef = expression.classReference
                val targetClass = classRef?.resolve() as? PsiClass
                val target = MixinTargetGenerator.generateRedirectTarget(constructor, targetClass)
                targets.add(target)
            }
        })
    }
}

