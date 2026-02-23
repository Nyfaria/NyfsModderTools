package com.nyfaria.nyfsmoddingplugin.mixin

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.util.TypeConversionUtil

object MixinTargetGenerator {

    fun generateMixinAnnotation(psiClass: PsiClass): String {
        val className = getInternalClassName(psiClass)
        return "@Mixin($className.class)"
    }

    fun generateInjectTarget(psiMethod: PsiMethod): String {
        val methodName = if (psiMethod.isConstructor) "<init>" else psiMethod.name
        val descriptor = getMethodDescriptor(psiMethod)
        return "method = \"$methodName$descriptor\""
    }

    fun generateAtInvokeTarget(psiMethod: PsiMethod, callSiteClass: PsiClass? = null): String {
        val ownerClass = callSiteClass ?: psiMethod.containingClass ?: return ""
        val className = getInternalClassName(ownerClass)
        val methodName = if (psiMethod.isConstructor) "<init>" else psiMethod.name
        val descriptor = getMethodDescriptor(psiMethod)
        return "@At(value = \"INVOKE\", target = \"L$className;$methodName$descriptor\")"
    }

    fun generateRedirectTarget(psiMethod: PsiMethod, callSiteClass: PsiClass? = null): String {
        val ownerClass = callSiteClass ?: psiMethod.containingClass ?: return ""
        val className = getInternalClassName(ownerClass)
        val methodName = if (psiMethod.isConstructor) "<init>" else psiMethod.name
        val descriptor = getMethodDescriptor(psiMethod)
        return "L$className;$methodName$descriptor"
    }

    fun generateAtFieldTarget(psiField: PsiField, opcode: FieldOpcode, callSiteClass: PsiClass? = null): String {
        val ownerClass = callSiteClass ?: psiField.containingClass ?: return ""
        val className = getInternalClassName(ownerClass)
        val fieldName = psiField.name
        val descriptor = getTypeDescriptor(psiField.type)
        return "@At(value = \"FIELD\", target = \"L$className;$fieldName:$descriptor\", opcode = Opcodes.${opcode.name})"
    }

    fun generateFieldTarget(psiField: PsiField, callSiteClass: PsiClass? = null): String {
        val ownerClass = callSiteClass ?: psiField.containingClass ?: return ""
        val className = getInternalClassName(ownerClass)
        val fieldName = psiField.name
        val descriptor = getTypeDescriptor(psiField.type)
        return "L$className;$fieldName:$descriptor"
    }

    fun generateAccessorAnnotation(psiField: PsiField): String {
        return "@Accessor(\"${psiField.name}\")"
    }

    fun generateInvokerAnnotation(psiMethod: PsiMethod): String {
        val methodName = if (psiMethod.isConstructor) "<init>" else psiMethod.name
        return "@Invoker(\"$methodName\")"
    }

    fun generateShadowField(psiField: PsiField): String {
        val typeName = psiField.type.presentableText
        val fieldName = psiField.name
        return "@Shadow\nprivate $typeName $fieldName;"
    }

    fun generateShadowMethod(psiMethod: PsiMethod): String {
        val returnType = psiMethod.returnType?.presentableText ?: "void"
        val methodName = psiMethod.name
        val params = psiMethod.parameterList.parameters.joinToString(", ") {
            "${it.type.presentableText} ${it.name}"
        }
        return "@Shadow\nprivate $returnType $methodName($params) { throw new AssertionError(); }"
    }

    private fun getMethodDescriptor(method: PsiMethod): String {
        val params = StringBuilder("(")
        for (param in method.parameterList.parameters) {
            params.append(getTypeDescriptor(param.type))
        }
        params.append(")")

        val returnType = method.returnType
        val returnDesc = if (returnType == null) "V" else getTypeDescriptor(returnType)

        return params.toString() + returnDesc
    }

    private fun getTypeDescriptor(type: PsiType): String {
        val erasedType = TypeConversionUtil.erasure(type)
        return getTypeDescriptorInternal(erasedType ?: type)
    }

    private fun getTypeDescriptorInternal(type: PsiType): String {
        return when (type) {
            is PsiPrimitiveType -> getPrimitiveDescriptor(type)
            is PsiArrayType -> "[" + getTypeDescriptorInternal(type.componentType)
            is PsiClassType -> {
                val resolved = type.resolve()
                if (resolved is PsiTypeParameter) {
                    "Ljava/lang/Object;"
                } else if (resolved != null) {
                    val internalName = getInternalClassName(resolved)
                    "L$internalName;"
                } else {
                    val fallback = type.canonicalText.replace('.', '/')
                    "L$fallback;"
                }
            }
            else -> {
                val fallback = type.canonicalText.replace('.', '/')
                "L$fallback;"
            }
        }
    }

    private fun getPrimitiveDescriptor(type: PsiPrimitiveType): String {
        return when (type.canonicalText) {
            "void" -> "V"
            "boolean" -> "Z"
            "byte" -> "B"
            "char" -> "C"
            "short" -> "S"
            "int" -> "I"
            "long" -> "J"
            "float" -> "F"
            "double" -> "D"
            else -> "V"
        }
    }

    private fun getInternalClassName(psiClass: PsiClass): String {
        val containingClass = psiClass.containingClass
        return if (containingClass != null) {
            getInternalClassName(containingClass) + "$" + psiClass.name
        } else {
            psiClass.qualifiedName?.replace('.', '/') ?: psiClass.name ?: ""
        }
    }

    enum class FieldOpcode {
        GETFIELD,
        PUTFIELD,
        GETSTATIC,
        PUTSTATIC
    }
}

