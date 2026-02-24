package com.nyfaria.nyfsmoddertools.mixin.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.nyfaria.nyfsmoddertools.mixin.MixinTargetGenerator
import com.nyfaria.nyfsmoddertools.settings.NyfsModdingSettings

class MixinMethodCompletionProvider : CompletionProvider<CompletionParameters>() {

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
        if (attrName != "method") return

        val annotation = PsiTreeUtil.getParentOfType(nameValuePair, PsiAnnotation::class.java) ?: return

        val annotationName = annotation.nameReferenceElement?.referenceName
        if (!MixinCompletionUtil.isInMethodParameter(annotation) &&
            annotationName !in setOf(
                "Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyConstant", "ModifyVariable", "Overwrite",
                "WrapMethod", "WrapOperation", "WrapWithCondition", "ModifyExpressionValue", "ModifyReturnValue", "ModifyReceiver"
            )) return

        result.stopHere()

        val containingClass = PsiTreeUtil.getParentOfType(annotation, PsiClass::class.java) ?: return
        val targetClasses = MixinCompletionUtil.findMixinTargetClasses(containingClass)

        if (targetClasses.isEmpty()) return

        val literalText = literal.text
        val offsetInLiteral = parameters.offset - literal.textRange.startOffset
        val prefix = if (offsetInLiteral > 1 && offsetInLiteral <= literalText.length) {
            literalText.substring(1, offsetInLiteral).replace("IntellijIdeaRulezzz", "")
        } else ""

        val resultWithPrefix = result.withPrefixMatcher(prefix)

        val addedSignatures = mutableSetOf<String>()

        for (targetClass in targetClasses) {
            addMethodCompletions(targetClass, resultWithPrefix, addedSignatures)
        }
    }

    private fun addMethodCompletions(
        targetClass: PsiClass,
        result: CompletionResultSet,
        addedSignatures: MutableSet<String>
    ) {
        for (method in targetClass.methods) {
            val signature = getMethodSignature(method)
            if (signature in addedSignatures) continue
            addedSignatures.add(signature)

            val lookupElement = createMethodLookupElement(method)
            result.addElement(PrioritizedLookupElement.withPriority(lookupElement, 100.0))
        }

        for (constructor in targetClass.constructors) {
            val signature = getMethodSignature(constructor)
            if (signature in addedSignatures) continue
            addedSignatures.add(signature)

            val lookupElement = createConstructorLookupElement(constructor)
            result.addElement(PrioritizedLookupElement.withPriority(lookupElement, 100.0))
        }
    }

    private fun createMethodLookupElement(method: PsiMethod): LookupElementBuilder {
        val methodTarget = MixinTargetGenerator.generateInjectTarget(method)
        val descriptor = methodTarget.removePrefix("method = \"").removeSuffix("\"")

        val paramTypes = method.parameterList.parameters.joinToString(", ") {
            it.type.presentableText
        }
        val returnType = method.returnType?.presentableText ?: "void"

        return LookupElementBuilder.create(descriptor)
            .withLookupString(method.name)
            .withPresentableText(method.name)
            .withTailText("($paramTypes)", true)
            .withTypeText(returnType)
            .withIcon(AllIcons.Nodes.Method)
    }

    private fun createConstructorLookupElement(constructor: PsiMethod): LookupElementBuilder {
        val methodTarget = MixinTargetGenerator.generateInjectTarget(constructor)
        val descriptor = methodTarget.removePrefix("method = \"").removeSuffix("\"")

        val paramTypes = constructor.parameterList.parameters.joinToString(", ") {
            it.type.presentableText
        }

        return LookupElementBuilder.create(descriptor)
            .withLookupString("<init>")
            .withLookupString(constructor.containingClass?.name ?: "")
            .withPresentableText("<init>")
            .withTailText("($paramTypes)", true)
            .withTypeText(constructor.containingClass?.name ?: "")
            .withIcon(AllIcons.Nodes.ClassInitializer)
    }

    private fun getMethodSignature(method: PsiMethod): String {
        val name = if (method.isConstructor) "<init>" else method.name
        val params = method.parameterList.parameters.joinToString(",") { it.type.canonicalText }
        return "$name($params)"
    }
}

