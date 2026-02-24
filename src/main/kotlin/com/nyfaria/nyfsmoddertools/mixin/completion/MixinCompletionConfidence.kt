package com.nyfaria.nyfsmoddertools.mixin.completion

import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ThreeState
import com.nyfaria.nyfsmoddertools.settings.NyfsModdingSettings

class MixinCompletionConfidence : CompletionConfidence() {

    @Deprecated("Deprecated in Java")
    override fun shouldSkipAutopopup(element: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
        if (!NyfsModdingSettings.getInstance().enableMixinAutocomplete) {
            return ThreeState.UNSURE
        }

        val literal = PsiTreeUtil.getParentOfType(element, PsiLiteralExpression::class.java)
            ?: return ThreeState.UNSURE

        val nameValuePair = PsiTreeUtil.getParentOfType(literal, PsiNameValuePair::class.java)
            ?: return ThreeState.UNSURE

        val attrName = nameValuePair.name ?: "value"

        val annotation = PsiTreeUtil.getParentOfType(nameValuePair, PsiAnnotation::class.java)
            ?: return ThreeState.UNSURE

        if (attrName == "method" && MixinCompletionUtil.isInMethodParameter(annotation)) {
            return ThreeState.NO
        }

        if (attrName == "target" && MixinCompletionUtil.isAtAnnotation(annotation)) {
            return ThreeState.NO
        }

        return ThreeState.UNSURE
    }
}

