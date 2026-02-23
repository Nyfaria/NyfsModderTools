package com.nyfaria.nyfsmoddingplugin.ataw

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType

object AccessTransformerGenerator {

    fun generateForClass(psiClass: PsiClass, makePublic: Boolean = true): String {
        val className = psiClass.qualifiedName ?: return ""
        val modifier = if (makePublic) "public" else "protected"
        return "$modifier $className"
    }

    fun generateForMethod(psiMethod: PsiMethod, makePublic: Boolean = true): String {
        val containingClass = psiMethod.containingClass ?: return ""
        val className = containingClass.qualifiedName ?: return ""
        val modifier = if (makePublic) "public" else "protected"
        val methodName = if (psiMethod.isConstructor) "<init>" else psiMethod.name
        val descriptor = getMethodDescriptor(psiMethod)
        return "$modifier $className $methodName$descriptor"
    }

    fun generateForField(psiField: PsiField, makePublic: Boolean = true, removeFinal: Boolean = false): String {
        val containingClass = psiField.containingClass ?: return ""
        val className = containingClass.qualifiedName ?: return ""

        val modifierParts = mutableListOf<String>()
        modifierParts.add(if (makePublic) "public" else "protected")
        if (removeFinal && psiField.hasModifierProperty(PsiModifier.FINAL)) {
            modifierParts.add("-f")
        }

        val modifier = modifierParts.joinToString("-")
        val fieldName = psiField.name
        return "$modifier $className $fieldName"
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
}

