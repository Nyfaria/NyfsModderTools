package com.nyfaria.nyfsmoddertools.mixin.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.patterns.PlatformPatterns

class MixinCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            MixinMethodCompletionProvider()
        )

        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            MixinAtTargetCompletionProvider()
        )
    }
}

