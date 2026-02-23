package com.nyfaria.nyfsmoddingplugin.ataw

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType

object AccessWidenerGenerator {

    fun generateForClass(psiClass: PsiClass, accessType: AccessType = AccessType.ACCESSIBLE): String {
        val className = getInternalClassName(psiClass)
        return "${accessType.keyword}\tclass\t$className"
    }

    fun generateForMethod(psiMethod: PsiMethod, accessType: AccessType = AccessType.ACCESSIBLE): String {
        val containingClass = psiMethod.containingClass ?: return ""
        val className = getInternalClassName(containingClass)
        val methodName = if (psiMethod.isConstructor) "<init>" else psiMethod.name
        val descriptor = getMethodDescriptor(psiMethod)
        return "${accessType.keyword}\tmethod\t$className\t$methodName\t$descriptor"
    }

    fun generateForField(psiField: PsiField, accessType: AccessType = AccessType.ACCESSIBLE): String {
        val containingClass = psiField.containingClass ?: return ""
        val className = getInternalClassName(containingClass)
        val fieldName = psiField.name
        val descriptor = getTypeDescriptor(psiField.type)
        return "${accessType.keyword}\tfield\t$className\t$fieldName\t$descriptor"
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
        return when (type) {
            is PsiPrimitiveType -> getPrimitiveDescriptor(type)
            is PsiArrayType -> "[" + getTypeDescriptor(type.componentType)
            is PsiClassType -> {
                val resolved = type.resolve()
                if (resolved != null) {
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

    enum class AccessType(val keyword: String) {
        ACCESSIBLE("accessible"),
        EXTENDABLE("extendable"),
        MUTABLE("mutable")
    }
}

