package com.nyfaria.nyfsmoddertools.mixin.inspection

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiTypes
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.nyfaria.nyfsmoddertools.mixin.MixinTargetGenerator
import com.nyfaria.nyfsmoddertools.mixin.completion.MixinCompletionUtil
import com.nyfaria.nyfsmoddertools.settings.NyfsModdingSettings

class MixinMethodSignatureInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun getDisplayName(): String = "Invalid Mixin method signature"

    override fun getShortName(): String = "MixinMethodSignatureInspection"

    override fun getGroupDisplayName(): String = "Mixin"

    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!NyfsModdingSettings.getInstance().enableMixinInspections) {
            return PsiElementVisitor.EMPTY_VISITOR
        }

        return object : JavaElementVisitor() {
            override fun visitMethod(method: PsiMethod) {
                super.visitMethod(method)

                for (annotation in method.annotations) {
                    val refName = annotation.nameReferenceElement?.referenceName ?: continue

                    when (refName) {
                        "Inject" -> checkInjectSignature(method, annotation, holder)
                        "Redirect" -> checkRedirectSignature(method, annotation, holder)
                        "ModifyArg" -> checkModifyArgSignature(method, annotation, holder)
                        "ModifyArgs" -> checkModifyArgsSignature(method, annotation, holder)
                        "ModifyVariable" -> checkModifyVariableSignature(method, annotation, holder)
                        "ModifyConstant" -> checkModifyConstantSignature(method, annotation, holder)
                        "WrapMethod" -> checkWrapMethodSignature(method, annotation, holder)
                        "WrapOperation" -> checkWrapOperationSignature(method, annotation, holder)
                        "WrapWithCondition" -> checkWrapWithConditionSignature(method, annotation, holder)
                        "ModifyExpressionValue" -> checkModifyExpressionValueSignature(method, annotation, holder)
                        "ModifyReturnValue" -> checkModifyReturnValueSignature(method, annotation, holder)
                        "ModifyReceiver" -> checkModifyReceiverSignature(method, annotation, holder)
                    }
                }
            }
        }
    }

    private fun checkInjectSignature(method: PsiMethod, annotation: PsiAnnotation, holder: ProblemsHolder) {
        val containingClass = method.containingClass ?: return
        val targetClasses = MixinCompletionUtil.findMixinTargetClasses(containingClass)
        if (targetClasses.isEmpty()) return

        val targetMethods = findTargetMethods(annotation, targetClasses)
        if (targetMethods.isEmpty()) return

        val targetMethod = targetMethods.first()
        val actualParams = method.parameterList.parameters

        val returnType = targetMethod.returnType
        val isVoid = returnType == null || returnType == PsiTypes.voidType()
        val callbackType = if (isVoid) "CallbackInfo" else "CallbackInfoReturnable"

        val hasCorrectCallback = actualParams.any { param ->
            val typeName = param.type.canonicalText
            typeName.contains("CallbackInfo")
        }

        if (!hasCorrectCallback) {
            holder.registerProblem(
                method.parameterList,
                "Inject method must have $callbackType parameter",
                ProblemHighlightType.GENERIC_ERROR,
                FixInjectSignatureQuickFix(targetMethod, isVoid, targetMethod.returnType?.presentableText)
            )
            return
        }

        val callbackParamIndex = actualParams.indexOfFirst { it.type.canonicalText.contains("CallbackInfo") }
        val paramsBeforeCallback = actualParams.take(callbackParamIndex)

        if (paramsBeforeCallback.size != targetMethod.parameterList.parametersCount) {
            holder.registerProblem(
                method.parameterList,
                "Inject method parameters before $callbackType should match target method parameters",
                ProblemHighlightType.GENERIC_ERROR,
                FixInjectSignatureQuickFix(targetMethod, isVoid, targetMethod.returnType?.presentableText)
            )
        }
    }

    private fun checkRedirectSignature(method: PsiMethod, annotation: PsiAnnotation, holder: ProblemsHolder) {
        val containingClass = method.containingClass ?: return
        val targetClasses = MixinCompletionUtil.findMixinTargetClasses(containingClass)
        if (targetClasses.isEmpty()) return

        val atAnnotation = findAtAnnotation(annotation) ?: return
        val atType = MixinCompletionUtil.getAtAnnotationType(atAnnotation) ?: return

        if (atType != "INVOKE") return

        val targetValue = getAtTargetValue(atAnnotation) ?: return
        val redirectedMethod = findMethodFromTarget(targetValue, containingClass.project) ?: return

        val expectedReturnType = redirectedMethod.returnType
        val actualReturnType = method.returnType

        if (expectedReturnType != null && actualReturnType != null) {
            if (!actualReturnType.isAssignableFrom(expectedReturnType) &&
                actualReturnType.canonicalText != expectedReturnType.canonicalText) {
                holder.registerProblem(
                    method.parameterList,
                    "Redirect method return type should be '${expectedReturnType.presentableText}'",
                    ProblemHighlightType.GENERIC_ERROR,
                    FixRedirectSignatureQuickFix(redirectedMethod, expectedReturnType.presentableText)
                )
            }
        }
    }

    private fun checkModifyArgSignature(method: PsiMethod, annotation: PsiAnnotation, holder: ProblemsHolder) {
        val params = method.parameterList.parameters
        if (params.isEmpty()) {
            holder.registerProblem(
                method.parameterList,
                "ModifyArg method must have at least one parameter (the argument being modified)",
                ProblemHighlightType.GENERIC_ERROR
            )
            return
        }

        val returnType = method.returnType
        val paramType = params[0].type

        if (returnType != null && returnType.canonicalText != paramType.canonicalText) {
            holder.registerProblem(
                method.parameterList,
                "ModifyArg method return type should match the parameter type '${paramType.presentableText}'",
                ProblemHighlightType.GENERIC_ERROR
            )
        }
    }

    private fun checkModifyArgsSignature(method: PsiMethod, annotation: PsiAnnotation, holder: ProblemsHolder) {
        val params = method.parameterList.parameters
        if (params.isEmpty() || !params[0].type.canonicalText.contains("Args")) {
            holder.registerProblem(
                method.parameterList,
                "ModifyArgs method must have an Args parameter",
                ProblemHighlightType.GENERIC_ERROR
            )
        }

        val returnType = method.returnType
        if (returnType != null && returnType != PsiTypes.voidType()) {
            holder.registerProblem(
                method.parameterList,
                "ModifyArgs method should return void",
                ProblemHighlightType.GENERIC_ERROR
            )
        }
    }

    private fun checkModifyVariableSignature(method: PsiMethod, annotation: PsiAnnotation, holder: ProblemsHolder) {
        val params = method.parameterList.parameters
        if (params.isEmpty()) {
            holder.registerProblem(
                method.parameterList,
                "ModifyVariable method must have at least one parameter (the variable being modified)",
                ProblemHighlightType.GENERIC_ERROR
            )
            return
        }

        val returnType = method.returnType
        val paramType = params[0].type

        if (returnType != null && returnType.canonicalText != paramType.canonicalText) {
            holder.registerProblem(
                method.parameterList,
                "ModifyVariable method return type should match the parameter type '${paramType.presentableText}'",
                ProblemHighlightType.GENERIC_ERROR
            )
        }
    }

    private fun checkModifyConstantSignature(method: PsiMethod, annotation: PsiAnnotation, holder: ProblemsHolder) {
        val params = method.parameterList.parameters
        if (params.isEmpty()) {
            holder.registerProblem(
                method.parameterList,
                "ModifyConstant method must have at least one parameter (the constant being modified)",
                ProblemHighlightType.GENERIC_ERROR
            )
            return
        }

        val returnType = method.returnType
        val paramType = params[0].type

        if (returnType != null && returnType.canonicalText != paramType.canonicalText) {
            holder.registerProblem(
                method.parameterList,
                "ModifyConstant method return type should match the parameter type '${paramType.presentableText}'",
                ProblemHighlightType.GENERIC_ERROR
            )
        }
    }

    private fun checkWrapMethodSignature(method: PsiMethod, annotation: PsiAnnotation, holder: ProblemsHolder) {
        val containingClass = method.containingClass ?: return
        val targetClasses = MixinCompletionUtil.findMixinTargetClasses(containingClass)
        if (targetClasses.isEmpty()) return

        val targetMethods = findTargetMethods(annotation, targetClasses)
        if (targetMethods.isEmpty()) return

        val targetMethod = targetMethods.first()
        val params = method.parameterList.parameters

        val hasOperation = params.any { it.type.canonicalText.contains("Operation") }
        if (!hasOperation) {
            holder.registerProblem(
                method.parameterList,
                "WrapMethod must have an Operation parameter as the last parameter",
                ProblemHighlightType.GENERIC_ERROR,
                FixWrapMethodSignatureQuickFix(targetMethod)
            )
            return
        }

        val expectedReturnType = targetMethod.returnType
        val actualReturnType = method.returnType
        if (expectedReturnType != null && actualReturnType != null &&
            expectedReturnType.canonicalText != actualReturnType.canonicalText) {
            holder.registerProblem(
                method.parameterList,
                "WrapMethod return type should be '${expectedReturnType.presentableText}'",
                ProblemHighlightType.GENERIC_ERROR,
                FixWrapMethodSignatureQuickFix(targetMethod)
            )
        }
    }

    private fun checkWrapOperationSignature(method: PsiMethod, annotation: PsiAnnotation, holder: ProblemsHolder) {
        val params = method.parameterList.parameters

        val hasOperation = params.any { it.type.canonicalText.contains("Operation") }
        if (!hasOperation) {
            holder.registerProblem(
                method.parameterList,
                "WrapOperation must have an Operation parameter",
                ProblemHighlightType.GENERIC_ERROR
            )
        }
    }

    private fun checkWrapWithConditionSignature(method: PsiMethod, annotation: PsiAnnotation, holder: ProblemsHolder) {
        val returnType = method.returnType

        if (returnType == null || returnType.canonicalText != "boolean") {
            holder.registerProblem(
                method.parameterList,
                "WrapWithCondition must return boolean",
                ProblemHighlightType.GENERIC_ERROR
            )
        }
    }

    private fun checkModifyExpressionValueSignature(method: PsiMethod, annotation: PsiAnnotation, holder: ProblemsHolder) {
        val params = method.parameterList.parameters
        if (params.isEmpty()) {
            holder.registerProblem(
                method.parameterList,
                "ModifyExpressionValue must have at least one parameter (the original value)",
                ProblemHighlightType.GENERIC_ERROR
            )
            return
        }

        val returnType = method.returnType
        val paramType = params[0].type

        if (returnType != null && returnType.canonicalText != paramType.canonicalText) {
            holder.registerProblem(
                method.parameterList,
                "ModifyExpressionValue return type should match the first parameter type '${paramType.presentableText}'",
                ProblemHighlightType.GENERIC_ERROR
            )
        }
    }

    private fun checkModifyReturnValueSignature(method: PsiMethod, annotation: PsiAnnotation, holder: ProblemsHolder) {
        val containingClass = method.containingClass ?: return
        val targetClasses = MixinCompletionUtil.findMixinTargetClasses(containingClass)
        if (targetClasses.isEmpty()) return

        val targetMethods = findTargetMethods(annotation, targetClasses)
        if (targetMethods.isEmpty()) return

        val targetMethod = targetMethods.first()
        val params = method.parameterList.parameters

        if (params.isEmpty()) {
            holder.registerProblem(
                method.parameterList,
                "ModifyReturnValue must have the return value as the first parameter",
                ProblemHighlightType.GENERIC_ERROR
            )
            return
        }

        val expectedReturnType = targetMethod.returnType
        val actualReturnType = method.returnType

        if (expectedReturnType != null && actualReturnType != null &&
            expectedReturnType.canonicalText != actualReturnType.canonicalText) {
            holder.registerProblem(
                method.parameterList,
                "ModifyReturnValue return type should be '${expectedReturnType.presentableText}'",
                ProblemHighlightType.GENERIC_ERROR
            )
        }
    }

    private fun checkModifyReceiverSignature(method: PsiMethod, annotation: PsiAnnotation, holder: ProblemsHolder) {
        val params = method.parameterList.parameters
        if (params.isEmpty()) {
            holder.registerProblem(
                method.parameterList,
                "ModifyReceiver must have the receiver as the first parameter",
                ProblemHighlightType.GENERIC_ERROR
            )
            return
        }

        val returnType = method.returnType
        val paramType = params[0].type

        if (returnType != null && returnType.canonicalText != paramType.canonicalText) {
            holder.registerProblem(
                method.parameterList,
                "ModifyReceiver return type should match the first parameter type '${paramType.presentableText}'",
                ProblemHighlightType.GENERIC_ERROR
            )
        }
    }

    private fun findTargetMethods(annotation: PsiAnnotation, targetClasses: List<PsiClass>): List<PsiMethod> {
        val result = mutableListOf<PsiMethod>()
        val methodAttr = annotation.findAttributeValue("method") ?: return result

        val descriptors = when (methodAttr) {
            is PsiLiteralExpression -> listOfNotNull(methodAttr.value as? String)
            is PsiArrayInitializerMemberValue -> methodAttr.initializers.mapNotNull {
                (it as? PsiLiteralExpression)?.value as? String
            }
            else -> emptyList()
        }

        for (targetClass in targetClasses) {
            for (descriptor in descriptors) {
                val methodName = descriptor.substringBefore("(").substringBefore("*")

                for (method in targetClass.methods) {
                    if (method.name == methodName || (methodName.endsWith("*") && method.name.startsWith(methodName.dropLast(1)))) {
                        if (!descriptor.contains("(")) {
                            result.add(method)
                        } else {
                            val actualDescriptor = MixinTargetGenerator.generateInjectTarget(method)
                                .removePrefix("method = \"").removeSuffix("\"")
                            if (actualDescriptor == descriptor) {
                                result.add(method)
                            }
                        }
                    }
                }

                if (methodName == "<init>") {
                    result.addAll(targetClass.constructors)
                }
            }
        }
        return result
    }

    private fun findAtAnnotation(annotation: PsiAnnotation): PsiAnnotation? {
        val atAttr = annotation.findAttributeValue("at") ?: return null
        return when (atAttr) {
            is PsiAnnotation -> atAttr
            else -> PsiTreeUtil.findChildOfType(atAttr, PsiAnnotation::class.java)
        }
    }

    private fun getAtTargetValue(atAnnotation: PsiAnnotation): String? {
        val targetAttr = atAnnotation.findAttributeValue("target")
        return (targetAttr as? PsiLiteralExpression)?.value as? String
    }

    private fun findMethodFromTarget(target: String, project: Project): PsiMethod? {
        val classEnd = target.indexOf(';')
        if (classEnd == -1) return null

        val className = target.substring(1, classEnd).replace('/', '.')
        val methodPart = target.substring(classEnd + 1)
        val methodName = methodPart.substringBefore("(")

        val facade = JavaPsiFacade.getInstance(project)
        val psiClass = facade.findClass(className, com.intellij.psi.search.GlobalSearchScope.allScope(project))
            ?: return null

        return psiClass.findMethodsByName(methodName, false).firstOrNull()
    }

    private class FixInjectSignatureQuickFix(
        targetMethod: PsiMethod,
        private val isVoid: Boolean,
        private val returnTypeName: String?
    ) : LocalQuickFix {

        private val targetMethodPointer: SmartPsiElementPointer<PsiMethod> =
            SmartPointerManager.createPointer(targetMethod)

        override fun getFamilyName(): String = "Fix Mixin method signature"

        override fun getName(): String = "Fix @Inject method signature"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val method = descriptor.psiElement.parent as? PsiMethod ?: return
            val targetMethod = targetMethodPointer.element ?: return
            val factory = JavaPsiFacade.getElementFactory(project)

            val targetParams = targetMethod.parameterList.parameters
            val callbackType = if (isVoid) {
                "org.spongepowered.asm.mixin.injection.callback.CallbackInfo"
            } else {
                "org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<${returnTypeName ?: "?"}>"
            }

            val newParamList = factory.createParameterList(
                (targetParams.map { it.name } + "ci").toTypedArray(),
                (targetParams.map { it.type } + factory.createTypeFromText(callbackType, method)).toTypedArray()
            )

            method.parameterList.replace(newParamList)

            JavaCodeStyleManager.getInstance(project).shortenClassReferences(method)
        }
    }

    private class FixRedirectSignatureQuickFix(
        redirectedMethod: PsiMethod,
        private val returnTypeName: String
    ) : LocalQuickFix {

        private val redirectedMethodPointer: SmartPsiElementPointer<PsiMethod> =
            SmartPointerManager.createPointer(redirectedMethod)

        override fun getFamilyName(): String = "Fix Mixin method signature"

        override fun getName(): String = "Fix @Redirect method signature"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = descriptor.psiElement
            val method = (if (element is PsiMethod) element else element.parent) as? PsiMethod ?: return
            val redirectedMethod = redirectedMethodPointer.element ?: return
            val factory = JavaPsiFacade.getElementFactory(project)

            val returnType = redirectedMethod.returnType ?: return
            val newReturnType = factory.createTypeElement(returnType)

            method.returnTypeElement?.replace(newReturnType)

            JavaCodeStyleManager.getInstance(project).shortenClassReferences(method)
        }
    }

    private class FixWrapMethodSignatureQuickFix(
        targetMethod: PsiMethod
    ) : LocalQuickFix {

        private val targetMethodPointer: SmartPsiElementPointer<PsiMethod> =
            SmartPointerManager.createPointer(targetMethod)
        private val returnTypeName: String? = targetMethod.returnType?.canonicalText

        override fun getFamilyName(): String = "Fix Mixin method signature"

        override fun getName(): String = "Fix @WrapMethod method signature"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val method = descriptor.psiElement.parent as? PsiMethod ?: return
            val targetMethod = targetMethodPointer.element ?: return
            val factory = JavaPsiFacade.getElementFactory(project)

            val targetParams = targetMethod.parameterList.parameters
            val isStatic = targetMethod.hasModifierProperty("static")

            val operationGenericType = if (returnTypeName == null || returnTypeName == "void") {
                "Void"
            } else {
                returnTypeName
            }
            val operationType = "com.llamalad7.mixinextras.injector.wrapoperation.Operation<$operationGenericType>"

            val paramNames = mutableListOf<String>()
            val paramTypes = mutableListOf<com.intellij.psi.PsiType>()

            if (!isStatic) {
                val containingClass = targetMethod.containingClass
                if (containingClass != null) {
                    paramNames.add("instance")
                    paramTypes.add(factory.createType(containingClass))
                }
            }

            targetParams.forEach {
                paramNames.add(it.name ?: "arg${paramNames.size}")
                paramTypes.add(it.type)
            }

            paramNames.add("original")
            paramTypes.add(factory.createTypeFromText(operationType, method))

            val newParamList = factory.createParameterList(
                paramNames.toTypedArray(),
                paramTypes.toTypedArray()
            )

            method.parameterList.replace(newParamList)

            if (returnTypeName != null && returnTypeName != "void") {
                val newReturnType = factory.createTypeFromText(returnTypeName, method)
                method.returnTypeElement?.replace(factory.createTypeElement(newReturnType))
            }

            JavaCodeStyleManager.getInstance(project).shortenClassReferences(method)
        }
    }
}

