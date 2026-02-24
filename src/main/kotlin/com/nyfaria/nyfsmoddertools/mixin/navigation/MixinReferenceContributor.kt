package com.nyfaria.nyfsmoddertools.mixin.navigation

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PsiJavaPatterns
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ProcessingContext
import com.nyfaria.nyfsmoddertools.mixin.completion.MixinCompletionUtil

class MixinReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiLiteralExpression::class.java)
                .withParent(PsiJavaPatterns.psiNameValuePair().withName("method")),
            MixinMethodReferenceProvider()
        )

        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiLiteralExpression::class.java)
                .withParent(PsiJavaPatterns.psiNameValuePair().withName("target")),
            MixinTargetReferenceProvider()
        )
    }
}

class MixinMethodReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val literal = element as? PsiLiteralExpression ?: return PsiReference.EMPTY_ARRAY
        val value = literal.value as? String ?: return PsiReference.EMPTY_ARRAY

        if (value.isBlank()) return PsiReference.EMPTY_ARRAY

        val containingClass = findContainingMixinClass(element) ?: return PsiReference.EMPTY_ARRAY
        val targetClasses = MixinCompletionUtil.findMixinTargetClasses(containingClass)
        if (targetClasses.isEmpty()) return PsiReference.EMPTY_ARRAY

        return arrayOf(MixinMethodReference(literal, value, targetClasses))
    }

    private fun findContainingMixinClass(element: PsiElement): PsiClass? {
        var current: PsiElement? = element
        while (current != null) {
            if (current is PsiClass && current.hasAnnotation("org.spongepowered.asm.mixin.Mixin")) {
                return current
            }
            current = current.parent
        }
        return null
    }
}

class MixinTargetReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val literal = element as? PsiLiteralExpression ?: return PsiReference.EMPTY_ARRAY
        val value = literal.value as? String ?: return PsiReference.EMPTY_ARRAY

        if (value.isBlank() || !value.startsWith("L")) return PsiReference.EMPTY_ARRAY

        return arrayOf(MixinAtTargetReference(literal, value))
    }
}

class MixinMethodReference(
    element: PsiLiteralExpression,
    private val methodTarget: String,
    private val targetClasses: List<PsiClass>
) : PsiReferenceBase<PsiLiteralExpression>(element, TextRange(1, element.textLength - 1)) {

    override fun resolve(): PsiElement? {
        val methodName = methodTarget.substringBefore("(")

        for (targetClass in targetClasses) {
            val methods = targetClass.findMethodsByName(methodName, false)
            if (methods.isNotEmpty()) {
                if (methodTarget.contains("(")) {
                    val descriptor = methodTarget.substringAfter("(").removeSuffix(")")
                    for (method in methods) {
                        if (matchesDescriptor(method, descriptor)) {
                            return method
                        }
                    }
                }
                return methods.firstOrNull()
            }
        }

        return null
    }

    private fun matchesDescriptor(method: PsiMethod, descriptor: String): Boolean {
        val expectedDescriptor = buildMethodDescriptor(method)
        return expectedDescriptor.contains(descriptor) || descriptor.contains(expectedDescriptor.substringBefore(")"))
    }

    private fun buildMethodDescriptor(method: PsiMethod): String {
        val params = method.parameterList.parameters.joinToString("") { getTypeDescriptor(it.type) }
        val returnType = method.returnType?.let { getTypeDescriptor(it) } ?: "V"
        return "$params)$returnType"
    }

    private fun getTypeDescriptor(type: PsiType): String {
        return when (type) {
            PsiTypes.voidType() -> "V"
            PsiTypes.booleanType() -> "Z"
            PsiTypes.byteType() -> "B"
            PsiTypes.charType() -> "C"
            PsiTypes.shortType() -> "S"
            PsiTypes.intType() -> "I"
            PsiTypes.longType() -> "J"
            PsiTypes.floatType() -> "F"
            PsiTypes.doubleType() -> "D"
            is PsiArrayType -> "[${getTypeDescriptor(type.componentType)}"
            is PsiClassType -> {
                val resolved = type.resolve()
                val qualifiedName = resolved?.qualifiedName ?: return "Ljava/lang/Object;"
                "L${qualifiedName.replace('.', '/')};"
            }
            else -> "Ljava/lang/Object;"
        }
    }
}

class MixinAtTargetReference(
    element: PsiLiteralExpression,
    private val target: String
) : PsiReferenceBase<PsiLiteralExpression>(element, TextRange(1, element.textLength - 1)) {

    override fun resolve(): PsiElement? {
        val targetParts = parseTarget(target)
        if (targetParts == null) return null

        val (ownerClass, methodName, _) = targetParts

        val project = element.project
        val scope = GlobalSearchScope.allScope(project)
        val targetClass = JavaPsiFacade.getInstance(project).findClass(ownerClass.replace('/', '.'), scope)
            ?: return null

        val methods = targetClass.findMethodsByName(methodName, true)
        return methods.firstOrNull()
    }

    private fun parseTarget(target: String): Triple<String, String, String>? {
        val regex = Regex("L([^;]+);([^(]+)\\(([^)]*\\)[^)]*)")
        val match = regex.find(target) ?: return null
        return Triple(match.groupValues[1], match.groupValues[2], match.groupValues[3])
    }
}

