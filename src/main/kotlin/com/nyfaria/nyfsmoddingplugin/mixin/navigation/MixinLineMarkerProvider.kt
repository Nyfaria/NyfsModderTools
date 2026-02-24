package com.nyfaria.nyfsmoddingplugin.mixin.navigation

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.nyfaria.nyfsmoddingplugin.mixin.MixinTargetGenerator
import com.nyfaria.nyfsmoddingplugin.mixin.completion.MixinCompletionUtil

class MixinLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        if (element is PsiIdentifier) {
            val parent = element.parent

            when (parent) {
                is PsiClass -> {
                    if (isMixinClass(parent)) {
                        addMixinClassMarker(element, parent, result)
                    } else {
                        addTargetClassMarker(element, parent, result)
                    }
                }
                is PsiMethod -> {
                    if (isMixinHandler(parent)) {
                        addMixinMethodMarker(element, parent, result)
                    }
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

    private fun isMixinHandler(method: PsiMethod): Boolean {
        val handlerAnnotations = setOf(
            "Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyConstant", "ModifyVariable", "Overwrite",
            "WrapMethod", "WrapOperation", "WrapWithCondition", "ModifyExpressionValue", "ModifyReturnValue", "ModifyReceiver"
        )

        return method.annotations.any { annotation ->
            annotation.nameReferenceElement?.referenceName in handlerAnnotations
        }
    }

    private fun addMixinClassMarker(
        element: PsiIdentifier,
        mixinClass: PsiClass,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        val targetClasses = MixinCompletionUtil.findMixinTargetClasses(mixinClass)
        if (targetClasses.isEmpty()) return

        val builder = NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
            .setTargets(targetClasses)
            .setTooltipText("Navigate to mixin target class")

        result.add(builder.createLineMarkerInfo(element))
    }

    private fun addTargetClassMarker(
        element: PsiIdentifier,
        targetClass: PsiClass,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        val qualifiedName = targetClass.qualifiedName ?: return
        if (!qualifiedName.startsWith("net.minecraft") && !qualifiedName.startsWith("com.mojang")) return

        val mixins = findMixinsTargetingClass(targetClass)
        if (mixins.isEmpty()) return

        val builder = NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementingMethod)
            .setTargets(mixins)
            .setTooltipText("${mixins.size} mixin(s) targeting this class")

        result.add(builder.createLineMarkerInfo(element))
    }

    private fun addMixinMethodMarker(
        element: PsiIdentifier,
        method: PsiMethod,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {
        val containingClass = method.containingClass ?: return
        if (!isMixinClass(containingClass)) return

        val targetMethods = findTargetMethodsForHandler(method, containingClass)
        if (targetMethods.isEmpty()) return

        val builder = NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
            .setTargets(targetMethods)
            .setTooltipText("Navigate to target method")

        result.add(builder.createLineMarkerInfo(element))
    }

    private fun findMixinsTargetingClass(targetClass: PsiClass): List<PsiClass> {
        val project = targetClass.project
        val scope = GlobalSearchScope.projectScope(project)
        val mixinAnnotation = JavaPsiFacade.getInstance(project)
            .findClass("org.spongepowered.asm.mixin.Mixin", scope) ?: return emptyList()

        val result = mutableListOf<PsiClass>()
        val targetQualifiedName = targetClass.qualifiedName ?: return emptyList()

        val mixinClasses = AnnotatedElementsSearch.searchPsiClasses(mixinAnnotation, scope)

        for (mixinClass in mixinClasses) {
            val targets = MixinCompletionUtil.findMixinTargetClasses(mixinClass)
            if (targets.any { it.qualifiedName == targetQualifiedName }) {
                result.add(mixinClass)
            }
        }

        return result
    }

    private fun findTargetMethodsForHandler(handler: PsiMethod, mixinClass: PsiClass): List<PsiMethod> {
        val result = mutableListOf<PsiMethod>()

        val methodAnnotations = setOf(
            "Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyConstant", "ModifyVariable", "Overwrite",
            "WrapMethod", "WrapOperation", "WrapWithCondition", "ModifyExpressionValue", "ModifyReturnValue", "ModifyReceiver"
        )

        for (annotation in handler.annotations) {
            val refName = annotation.nameReferenceElement?.referenceName ?: continue
            if (refName !in methodAnnotations) continue

            val methodAttr = annotation.findAttributeValue("method") ?: continue
            val methodDescriptors = extractMethodDescriptors(methodAttr)

            val targetClasses = MixinCompletionUtil.findMixinTargetClasses(mixinClass)

            for (targetClass in targetClasses) {
                for (descriptor in methodDescriptors) {
                    val methodName = descriptor.substringBefore("(").substringBefore("*")

                    for (method in targetClass.methods) {
                        if (matchesDescriptor(method, descriptor, methodName)) {
                            result.add(method)
                        }
                    }

                    if (methodName == "<init>") {
                        for (constructor in targetClass.constructors) {
                            if (matchesDescriptor(constructor, descriptor, "<init>")) {
                                result.add(constructor)
                            }
                        }
                    }
                }
            }
        }

        return result
    }

    private fun extractMethodDescriptors(attr: PsiAnnotationMemberValue): List<String> {
        val result = mutableListOf<String>()
        when (attr) {
            is PsiLiteralExpression -> {
                (attr.value as? String)?.let { result.add(it) }
            }
            is PsiArrayInitializerMemberValue -> {
                for (initializer in attr.initializers) {
                    if (initializer is PsiLiteralExpression) {
                        (initializer.value as? String)?.let { result.add(it) }
                    }
                }
            }
        }
        return result
    }

    private fun matchesDescriptor(method: PsiMethod, descriptor: String, methodName: String): Boolean {
        val actualName = if (method.isConstructor) "<init>" else method.name

        if (methodName.endsWith("*")) {
            if (!actualName.startsWith(methodName.dropLast(1))) return false
        } else if (actualName != methodName) {
            return false
        }

        if (!descriptor.contains("(")) return true

        val expectedDescriptor = MixinTargetGenerator.generateInjectTarget(method)
            .removePrefix("method = \"").removeSuffix("\"")

        return expectedDescriptor == descriptor
    }
}

