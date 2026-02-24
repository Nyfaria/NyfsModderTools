package com.nyfaria.nyfsmoddertools.mixin.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.nyfaria.nyfsmoddertools.mixin.MixinTargetGenerator
import com.nyfaria.nyfsmoddertools.settings.NyfsModdingSettings

class MixinAtTargetCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        if (!NyfsModdingSettings.getInstance().enableMixinAutocomplete) return

        val position = parameters.position

        var literal = PsiTreeUtil.getParentOfType(position, PsiLiteralExpression::class.java)
        if (literal == null) {
            val originalFile = parameters.originalFile
            val offset = parameters.offset
            val elementAtOffset = originalFile.findElementAt(offset) ?: originalFile.findElementAt(offset - 1)
            literal = PsiTreeUtil.getParentOfType(elementAtOffset, PsiLiteralExpression::class.java)
        }
        if (literal == null) return

        val nameValuePair = PsiTreeUtil.getParentOfType(literal, PsiNameValuePair::class.java) ?: return

        val attrName = nameValuePair.name?.trim() ?: "value"
        if (attrName != "target") return

        val atAnnotation = PsiTreeUtil.getParentOfType(nameValuePair, PsiAnnotation::class.java) ?: return

        val atAnnotationName = atAnnotation.nameReferenceElement?.referenceName?.trim()
        val isAt = MixinCompletionUtil.isAtAnnotation(atAnnotation) || atAnnotationName == "At"
        if (!isAt) return

        result.stopHere()

        val parentAnnotation = findParentInjectionAnnotation(atAnnotation) ?: return

        val containingClass = PsiTreeUtil.getParentOfType(parentAnnotation, PsiClass::class.java) ?: return
        val targetClasses = MixinCompletionUtil.findMixinTargetClasses(containingClass)

        if (targetClasses.isEmpty()) return

        val methodDescriptors = getMethodDescriptorsFromAnnotation(parentAnnotation)
        if (methodDescriptors.isEmpty()) return

        val targetMethods = findTargetMethods(targetClasses, methodDescriptors)
        if (targetMethods.isEmpty()) return

        val atType = MixinCompletionUtil.getAtAnnotationType(atAnnotation)

        val literalText = literal.text
        val offsetInLiteral = parameters.offset - literal.textRange.startOffset
        val prefix = if (offsetInLiteral > 1 && offsetInLiteral <= literalText.length) {
            literalText.substring(1, offsetInLiteral).replace("IntellijIdeaRulezzz", "")
        } else ""

        val resultWithPrefix = result.withPrefixMatcher(prefix)

        when (atType) {
            "INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING" -> {
                addMethodTargetCompletions(targetMethods, resultWithPrefix)
            }
            "FIELD" -> {
                addFieldTargetCompletions(targetMethods, resultWithPrefix)
            }
            "NEW" -> {
                addConstructorTargetCompletions(targetMethods, resultWithPrefix)
            }
            null, "" -> {
                addMethodTargetCompletions(targetMethods, resultWithPrefix)
                addFieldTargetCompletions(targetMethods, resultWithPrefix)
            }
            else -> {
                addMethodTargetCompletions(targetMethods, resultWithPrefix)
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

    private fun addMethodTargetCompletions(targetMethods: List<PsiMethod>, result: CompletionResultSet) {
        val addedSignatures = mutableSetOf<String>()

        for (method in targetMethods) {
            val referencedMethods = findMethodCallsInMethod(method)
            for ((calledMethod, callSiteClass) in referencedMethods) {
                val target = MixinTargetGenerator.generateRedirectTarget(calledMethod, callSiteClass)
                if (target in addedSignatures) continue
                addedSignatures.add(target)

                val lookupElement = createMethodTargetLookupElement(calledMethod, target, callSiteClass)
                result.addElement(PrioritizedLookupElement.withPriority(lookupElement, 100.0))
            }
        }
    }

    private fun findMethodCallsInMethod(method: PsiMethod): List<Pair<PsiMethod, PsiClass?>> {
        val result = mutableListOf<Pair<PsiMethod, PsiClass?>>()

        val sourceMethod = method.navigationElement as? PsiMethod ?: method
        val body = sourceMethod.body ?: method.body ?: return result

        body.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                val resolvedMethod = expression.resolveMethod() ?: return
                val qualifierType = expression.methodExpression.qualifierExpression?.type
                val callSiteClass = (qualifierType as? PsiClassType)?.resolve()
                result.add(resolvedMethod to callSiteClass)
            }
        })

        return result
    }

    private fun createMethodTargetLookupElement(
        method: PsiMethod,
        target: String,
        callSiteClass: PsiClass? = null
    ): LookupElementBuilder {
        val paramTypes = method.parameterList.parameters.joinToString(", ") {
            it.type.presentableText
        }
        val returnType = method.returnType?.presentableText ?: "void"
        val ownerClass = callSiteClass?.name ?: method.containingClass?.name ?: ""

        return LookupElementBuilder.create(target)
            .withLookupString(method.name)
            .withLookupString(ownerClass)
            .withPresentableText("$ownerClass.${method.name}")
            .withTailText("($paramTypes)", true)
            .withTypeText(returnType)
            .withIcon(AllIcons.Nodes.Method)
    }

    private fun addFieldTargetCompletions(targetMethods: List<PsiMethod>, result: CompletionResultSet) {
        val addedSignatures = mutableSetOf<String>()

        for (method in targetMethods) {
            val referencedFields = findFieldReferencesInMethod(method)
            for ((field, callSiteClass) in referencedFields) {
                val target = MixinTargetGenerator.generateFieldTarget(field, callSiteClass)
                if (target in addedSignatures) continue
                addedSignatures.add(target)

                val lookupElement = createFieldTargetLookupElement(field, target, callSiteClass)
                result.addElement(PrioritizedLookupElement.withPriority(lookupElement, 100.0))
            }
        }
    }

    private fun findFieldReferencesInMethod(method: PsiMethod): List<Pair<PsiField, PsiClass?>> {
        val result = mutableListOf<Pair<PsiField, PsiClass?>>()

        val sourceMethod = method.navigationElement as? PsiMethod ?: method
        val body = sourceMethod.body ?: method.body ?: return result

        body.accept(object : JavaRecursiveElementVisitor() {
            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                super.visitReferenceExpression(expression)
                val resolved = expression.resolve()
                if (resolved is PsiField) {
                    val qualifierType = expression.qualifierExpression?.type
                    val callSiteClass = (qualifierType as? PsiClassType)?.resolve()
                    result.add(resolved to callSiteClass)
                }
            }
        })

        return result
    }

    private fun createFieldTargetLookupElement(
        field: PsiField,
        target: String,
        callSiteClass: PsiClass? = null
    ): LookupElementBuilder {
        val fieldType = field.type.presentableText
        val ownerClass = callSiteClass?.name ?: field.containingClass?.name ?: ""

        return LookupElementBuilder.create(target)
            .withLookupString(field.name)
            .withLookupString(ownerClass)
            .withPresentableText("$ownerClass.${field.name}")
            .withTypeText(fieldType)
            .withIcon(AllIcons.Nodes.Field)
    }

    private fun addConstructorTargetCompletions(targetMethods: List<PsiMethod>, result: CompletionResultSet) {
        val addedSignatures = mutableSetOf<String>()

        for (method in targetMethods) {
            val referencedConstructors = findConstructorCallsInMethod(method)
            for ((constructor, targetClass) in referencedConstructors) {
                val target = MixinTargetGenerator.generateRedirectTarget(constructor, targetClass)
                if (target in addedSignatures) continue
                addedSignatures.add(target)

                val lookupElement = createConstructorTargetLookupElement(constructor, target)
                result.addElement(PrioritizedLookupElement.withPriority(lookupElement, 100.0))
            }
        }
    }

    private fun findConstructorCallsInMethod(method: PsiMethod): List<Pair<PsiMethod, PsiClass?>> {
        val result = mutableListOf<Pair<PsiMethod, PsiClass?>>()

        val sourceMethod = method.navigationElement as? PsiMethod ?: method
        val body = sourceMethod.body ?: method.body ?: return result

        body.accept(object : JavaRecursiveElementVisitor() {
            override fun visitNewExpression(expression: PsiNewExpression) {
                super.visitNewExpression(expression)
                val constructor = expression.resolveConstructor() ?: return
                val classRef = expression.classReference
                val targetClass = classRef?.resolve() as? PsiClass
                result.add(constructor to targetClass)
            }
        })

        return result
    }

    private fun createConstructorTargetLookupElement(constructor: PsiMethod, target: String): LookupElementBuilder {
        val paramTypes = constructor.parameterList.parameters.joinToString(", ") {
            it.type.presentableText
        }
        val className = constructor.containingClass?.name ?: ""

        return LookupElementBuilder.create(target)
            .withLookupString("<init>")
            .withLookupString(className)
            .withPresentableText("$className.<init>")
            .withTailText("($paramTypes)", true)
            .withIcon(AllIcons.Nodes.ClassInitializer)
    }
}

