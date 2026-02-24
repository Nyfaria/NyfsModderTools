package com.nyfaria.nyfsmoddertools.mixin.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.nyfaria.nyfsmoddertools.mixin.MixinTargetGenerator
import com.nyfaria.nyfsmoddertools.settings.NyfsModdingSettings

class GenerateInvokeMixinAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        if (!NyfsModdingSettings.getInstance().enableGenerateMixin) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val methodCall = getTargetMethodCall(e)
        val containingMethod = getContainingMethod(e)
        val isValid = methodCall != null && containingMethod != null && isMinecraftMethod(containingMethod)
        e.presentation.isEnabledAndVisible = isValid
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val methodCall = getTargetMethodCall(e) ?: return
        val containingMethod = getContainingMethod(e) ?: return
        val containingClass = containingMethod.containingClass ?: return
        val calledMethod = methodCall.resolveMethod() ?: return

        val invokeTarget = generateInvokeTarget(calledMethod)

        val mixinTypes = listOf(
            InvokeMixinType("@Inject at INVOKE", "Inject"),
            InvokeMixinType("@Redirect at INVOKE", "Redirect"),
            InvokeMixinType("@WrapOperation at INVOKE", "WrapOperation"),
            InvokeMixinType("@ModifyReturnValue at INVOKE", "ModifyReturnValue")
        )

        val step = object : BaseListPopupStep<InvokeMixinType>("Select Mixin Type", mixinTypes) {
            override fun getTextFor(value: InvokeMixinType): String = value.displayName

            override fun onChosen(selectedValue: InvokeMixinType, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    generateInvokeMixinAsync(project, containingMethod, containingClass, calledMethod, invokeTarget, selectedValue)
                }
                return FINAL_CHOICE
            }
        }

        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor != null) {
            JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(editor)
        } else {
            JBPopupFactory.getInstance().createListPopup(step).showInFocusCenter()
        }
    }

    private fun getTargetMethodCall(e: AnActionEvent): PsiMethodCallExpression? {
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset)
        return PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java)
    }

    private fun getContainingMethod(e: AnActionEvent): PsiMethod? {
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset)
        return PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
    }

    private fun isMinecraftMethod(method: PsiMethod): Boolean {
        val qualifiedName = method.containingClass?.qualifiedName ?: return false
        return qualifiedName.startsWith("net.minecraft") || qualifiedName.startsWith("com.mojang")
    }

    private fun generateInvokeTarget(method: PsiMethod): String {
        val containingClass = method.containingClass ?: return ""
        val className = containingClass.qualifiedName?.replace('.', '/') ?: return ""
        val methodName = method.name
        val descriptor = buildMethodDescriptor(method)
        return "L$className;$methodName$descriptor"
    }

    private fun buildMethodDescriptor(method: PsiMethod): String {
        val params = method.parameterList.parameters.joinToString("") { getTypeDescriptor(it.type) }
        val returnType = method.returnType?.let { getTypeDescriptor(it) } ?: "V"
        return "($params)$returnType"
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

    private fun generateInvokeMixinAsync(
        project: Project,
        containingMethod: PsiMethod,
        containingClass: PsiClass,
        calledMethod: PsiMethod,
        invokeTarget: String,
        mixinType: InvokeMixinType
    ) {
        ReadAction.nonBlocking<InvokeMixinContext> {
            val existingMixin = findExistingMixinForClass(project, containingClass)
            val mixinPackages = if (existingMixin == null) findMixinPackages(project) else emptyList()
            val displayNames = mixinPackages.map { dir -> getModuleDisplayName(dir) }
            InvokeMixinContext(existingMixin, mixinPackages, displayNames)
        }
        .finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState()) { context ->
            if (context.existingMixin != null) {
                addInvokeHandlerToMixin(project, context.existingMixin, containingMethod, calledMethod, invokeTarget, mixinType)
            } else if (context.mixinPackages.isEmpty()) {
                showNoMixinPackageError(project)
            } else if (context.mixinPackages.size == 1) {
                createMixinWithInvokeHandler(project, context.mixinPackages.first(), containingClass, containingMethod, calledMethod, invokeTarget, mixinType)
            } else {
                showMixinLocationPopup(project, context.mixinPackages, context.displayNames, containingClass, containingMethod, calledMethod, invokeTarget, mixinType)
            }
        }
        .submit(AppExecutorUtil.getAppExecutorService())
    }

    private data class InvokeMixinContext(
        val existingMixin: PsiClass?,
        val mixinPackages: List<PsiDirectory>,
        val displayNames: List<String>
    )

    private fun showNoMixinPackageError(project: Project) {
        com.intellij.openapi.ui.Messages.showErrorDialog(
            project,
            "No existing mixin packages found. Please create a mixin class manually first.",
            "Cannot Generate Mixin"
        )
    }

    private fun getModuleDisplayName(dir: PsiDirectory): String {
        val path = dir.virtualFile.path
        return extractModuleName(path) ?: dir.name
    }

    private fun extractModuleName(path: String): String? {
        val normalizedPath = path.replace("\\", "/")
        val modulePatterns = listOf("common", "fabric", "neoforge", "forge", "quilt")
        for (module in modulePatterns) {
            if (normalizedPath.contains("/$module/")) {
                return module
            }
        }
        return null
    }

    private fun findExistingMixinForClass(project: Project, targetClass: PsiClass): PsiClass? {
        val scope = GlobalSearchScope.projectScope(project)
        val mixinAnnotation = JavaPsiFacade.getInstance(project)
            .findClass("org.spongepowered.asm.mixin.Mixin", scope) ?: return null

        val mixinClasses = AnnotatedElementsSearch.searchPsiClasses(mixinAnnotation, scope)

        for (mixinClass in mixinClasses) {
            val annotation = mixinClass.getAnnotation("org.spongepowered.asm.mixin.Mixin") ?: continue
            val targets = getMixinTargets(annotation)
            if (targets.contains(targetClass.qualifiedName)) {
                return mixinClass
            }
        }
        return null
    }

    private fun getMixinTargets(annotation: PsiAnnotation): Set<String> {
        val targets = mutableSetOf<String>()
        val valueAttr = annotation.findAttributeValue("value")
        if (valueAttr != null) {
            extractClassNames(valueAttr, targets)
        }
        return targets
    }

    private fun extractClassNames(expr: PsiElement, targets: MutableSet<String>) {
        when (expr) {
            is PsiClassObjectAccessExpression -> {
                val type = expr.operand.type as? PsiClassType
                type?.resolve()?.qualifiedName?.let { targets.add(it) }
            }
            else -> expr.children.forEach { extractClassNames(it, targets) }
        }
    }

    private fun findMixinPackages(project: Project): List<PsiDirectory> {
        val result = mutableListOf<PsiDirectory>()
        val scope = GlobalSearchScope.projectScope(project)

        val mixinAnnotation = JavaPsiFacade.getInstance(project)
            .findClass("org.spongepowered.asm.mixin.Mixin", scope)

        if (mixinAnnotation != null) {
            val mixinClasses = AnnotatedElementsSearch.searchPsiClasses(mixinAnnotation, scope)
            for (mixinClass in mixinClasses) {
                val dir = mixinClass.containingFile?.containingDirectory
                if (dir != null && dir !in result && isInGradleModule(dir)) {
                    result.add(dir)
                }
            }
        }

        if (result.isEmpty()) {
            findMixinDirectoriesByName(project, result)
        }

        findModuleMixinDirectories(project, result)

        return result.filter { isInGradleModule(it) }
    }

    private fun isInGradleModule(dir: PsiDirectory): Boolean {
        var current: com.intellij.openapi.vfs.VirtualFile? = dir.virtualFile
        while (current != null) {
            if (current.findChild("build.gradle") != null ||
                current.findChild("build.gradle.kts") != null) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun findMixinDirectoriesByName(project: Project, result: MutableList<PsiDirectory>) {
        val psiManager = PsiManager.getInstance(project)
        val projectScope = GlobalSearchScope.projectScope(project)

        com.intellij.psi.search.FilenameIndex.getAllFilesByExt(project, "java", projectScope)
            .asSequence()
            .mapNotNull { it.parent }
            .filter { dir ->
                val path = dir.path.lowercase()
                path.contains("/mixin/") || path.contains("\\mixin\\") ||
                path.endsWith("/mixin") || path.endsWith("\\mixin")
            }
            .mapNotNull { psiManager.findDirectory(it) }
            .filter { isInGradleModule(it) }
            .distinct()
            .forEach { dir ->
                if (dir !in result) {
                    result.add(dir)
                }
            }
    }

    private fun findModuleMixinDirectories(project: Project, result: MutableList<PsiDirectory>) {
        val psiManager = PsiManager.getInstance(project)
        val moduleNames = listOf("common", "fabric", "neoforge", "forge", "quilt")

        val baseDir = project.basePath?.let {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it)
        } ?: return

        for (moduleName in moduleNames) {
            val moduleDir = baseDir.findChild(moduleName) ?: continue
            findMixinDirInModule(moduleDir, psiManager, result)
        }
    }

    private fun findMixinDirInModule(
        moduleDir: com.intellij.openapi.vfs.VirtualFile,
        psiManager: PsiManager,
        result: MutableList<PsiDirectory>
    ) {
        val srcMain = moduleDir.findChild("src")?.findChild("main")?.findChild("java") ?: return
        findMixinDirsRecursively(srcMain, psiManager, result, maxDepth = 10)
    }

    private fun findMixinDirsRecursively(
        dir: com.intellij.openapi.vfs.VirtualFile,
        psiManager: PsiManager,
        result: MutableList<PsiDirectory>,
        maxDepth: Int = 10,
        currentDepth: Int = 0
    ) {
        if (!dir.isDirectory || currentDepth > maxDepth) return

        val name = dir.name.lowercase()
        if (name == "mixin" || name == "mixins") {
            val psiDir = psiManager.findDirectory(dir)
            if (psiDir != null && psiDir !in result) {
                result.add(psiDir)
            }
            return
        }

        for (child in dir.children) {
            if (child.isDirectory && child.name != "build" && child.name != ".gradle") {
                findMixinDirsRecursively(child, psiManager, result, maxDepth, currentDepth + 1)
            }
        }
    }

    private fun showMixinLocationPopup(
        project: Project,
        mixinPackages: List<PsiDirectory>,
        displayNames: List<String>,
        targetClass: PsiClass,
        containingMethod: PsiMethod,
        calledMethod: PsiMethod,
        invokeTarget: String,
        mixinType: InvokeMixinType
    ) {
        val packageMap = mixinPackages.zip(displayNames).toMap()

        val step = object : BaseListPopupStep<PsiDirectory>("Select Mixin Package", mixinPackages) {
            override fun getTextFor(value: PsiDirectory): String {
                return packageMap[value] ?: value.name
            }

            override fun onChosen(selectedValue: PsiDirectory, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    ApplicationManager.getApplication().invokeLater {
                        createMixinWithInvokeHandler(project, selectedValue, targetClass, containingMethod, calledMethod, invokeTarget, mixinType)
                    }
                }
                return FINAL_CHOICE
            }
        }

        JBPopupFactory.getInstance().createListPopup(step).showInFocusCenter()
    }

    private fun createMixinWithInvokeHandler(
        project: Project,
        directory: PsiDirectory,
        targetClass: PsiClass,
        containingMethod: PsiMethod,
        calledMethod: PsiMethod,
        invokeTarget: String,
        mixinType: InvokeMixinType
    ) {
        val className = "${targetClass.name}Mixin"

        WriteCommandAction.runWriteCommandAction(project) {
            val existingFile = directory.findFile("$className.java")
            if (existingFile != null) {
                val psiFile = PsiManager.getInstance(project).findFile(existingFile.virtualFile) as? PsiJavaFile
                val existingClass = psiFile?.classes?.firstOrNull()
                if (existingClass != null) {
                    addInvokeHandlerToMixin(project, existingClass, containingMethod, calledMethod, invokeTarget, mixinType)
                    return@runWriteCommandAction
                }
            }

            val packageName = JavaDirectoryService.getInstance().getPackage(directory)?.qualifiedName ?: ""
            val targetClassName = targetClass.qualifiedName ?: targetClass.name ?: return@runWriteCommandAction

            val classText = buildString {
                if (packageName.isNotEmpty()) {
                    append("package $packageName;\n\n")
                }
                append("import org.spongepowered.asm.mixin.Mixin;\n")
                appendImportsForInvokeMixin(mixinType, calledMethod)
                appendTypeImports(containingMethod)
                append("\n")
                append("@Mixin($targetClassName.class)\n")
                append("public class $className {\n")
                append(generateInvokeHandler(containingMethod, calledMethod, invokeTarget, mixinType))
                append("}\n")
            }

            val psiFile = PsiFileFactory.getInstance(project).createFileFromText(
                "$className.java",
                com.intellij.lang.java.JavaLanguage.INSTANCE,
                classText
            )
            val addedFile = directory.add(psiFile) as PsiFile

            JavaCodeStyleManager.getInstance(project).shortenClassReferences(addedFile)

            val virtualFile = addedFile.virtualFile
            if (virtualFile != null) {
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
            }
        }
    }

    private fun addInvokeHandlerToMixin(
        project: Project,
        mixinClass: PsiClass,
        containingMethod: PsiMethod,
        calledMethod: PsiMethod,
        invokeTarget: String,
        mixinType: InvokeMixinType
    ) {
        val factory = JavaPsiFacade.getElementFactory(project)

        WriteCommandAction.runWriteCommandAction(project) {
            val handlerCode = generateInvokeHandler(containingMethod, calledMethod, invokeTarget, mixinType)
            val method = factory.createMethodFromText(handlerCode, mixinClass)

            val addedMethod = mixinClass.add(method) as PsiMethod

            addImportsForInvokeMixin(project, mixinClass.containingFile as PsiJavaFile, mixinType, calledMethod, containingMethod)

            JavaCodeStyleManager.getInstance(project).shortenClassReferences(mixinClass)

            val virtualFile = mixinClass.containingFile?.virtualFile
            if (virtualFile != null) {
                val offset = addedMethod.textOffset
                FileEditorManager.getInstance(project).openTextEditor(
                    OpenFileDescriptor(project, virtualFile, offset), true
                )
            }
        }
    }

    private fun StringBuilder.appendImportsForInvokeMixin(mixinType: InvokeMixinType, calledMethod: PsiMethod) {
        when (mixinType.annotation) {
            "Inject" -> {
                append("import org.spongepowered.asm.mixin.injection.Inject;\n")
                append("import org.spongepowered.asm.mixin.injection.At;\n")
                append("import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;\n")
            }
            "Redirect" -> {
                append("import org.spongepowered.asm.mixin.injection.Redirect;\n")
                append("import org.spongepowered.asm.mixin.injection.At;\n")
            }
            "WrapOperation" -> {
                append("import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;\n")
                append("import com.llamalad7.mixinextras.injector.wrapoperation.Operation;\n")
                append("import org.spongepowered.asm.mixin.injection.At;\n")
            }
            "ModifyReturnValue" -> {
                append("import com.llamalad7.mixinextras.injector.ModifyReturnValue;\n")
                append("import org.spongepowered.asm.mixin.injection.At;\n")
            }
        }

        calledMethod.containingClass?.qualifiedName?.let {
            if (!it.startsWith("java.lang.")) {
                append("import $it;\n")
            }
        }
    }

    private fun StringBuilder.appendTypeImports(method: PsiMethod) {
        val imports = mutableSetOf<String>()

        for (param in method.parameterList.parameters) {
            collectTypeImportStrings(param.type, imports)
        }
        method.returnType?.let { collectTypeImportStrings(it, imports) }
        method.containingClass?.qualifiedName?.let { imports.add(it) }

        for (importFqn in imports.sorted()) {
            append("import $importFqn;\n")
        }
    }

    private fun collectTypeImportStrings(type: PsiType, imports: MutableSet<String>) {
        when (type) {
            is PsiClassType -> {
                val resolved = type.resolve()
                val qualifiedName = resolved?.qualifiedName
                if (qualifiedName != null && !qualifiedName.startsWith("java.lang.") && qualifiedName.contains(".")) {
                    imports.add(qualifiedName)
                }
                for (typeArg in type.parameters) {
                    collectTypeImportStrings(typeArg, imports)
                }
            }
            is PsiArrayType -> {
                collectTypeImportStrings(type.componentType, imports)
            }
        }
    }

    private fun addImportsForInvokeMixin(
        project: Project,
        file: PsiJavaFile,
        mixinType: InvokeMixinType,
        calledMethod: PsiMethod,
        containingMethod: PsiMethod
    ) {
        val factory = JavaPsiFacade.getElementFactory(project)
        val importList = file.importList ?: return

        val importsToAdd = mutableListOf<String>()

        when (mixinType.annotation) {
            "Inject" -> {
                importsToAdd.add("org.spongepowered.asm.mixin.injection.Inject")
                importsToAdd.add("org.spongepowered.asm.mixin.injection.At")
                importsToAdd.add("org.spongepowered.asm.mixin.injection.callback.CallbackInfo")
            }
            "Redirect" -> {
                importsToAdd.add("org.spongepowered.asm.mixin.injection.Redirect")
                importsToAdd.add("org.spongepowered.asm.mixin.injection.At")
            }
            "WrapOperation" -> {
                importsToAdd.add("com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation")
                importsToAdd.add("com.llamalad7.mixinextras.injector.wrapoperation.Operation")
                importsToAdd.add("org.spongepowered.asm.mixin.injection.At")
            }
            "ModifyReturnValue" -> {
                importsToAdd.add("com.llamalad7.mixinextras.injector.ModifyReturnValue")
                importsToAdd.add("org.spongepowered.asm.mixin.injection.At")
            }
        }

        collectTypeImports(calledMethod, importsToAdd)
        collectTypeImports(containingMethod, importsToAdd)

        for (importFqn in importsToAdd) {
            val existingImport = importList.importStatements.any {
                it.qualifiedName == importFqn
            }
            if (!existingImport) {
                val importClass = JavaPsiFacade.getInstance(project)
                    .findClass(importFqn, GlobalSearchScope.allScope(project))
                if (importClass != null) {
                    importList.add(factory.createImportStatement(importClass))
                }
            }
        }
    }

    private fun collectTypeImports(method: PsiMethod, importsToAdd: MutableList<String>) {
        for (param in method.parameterList.parameters) {
            collectTypeImport(param.type, importsToAdd)
        }
        method.returnType?.let { collectTypeImport(it, importsToAdd) }
        method.containingClass?.qualifiedName?.let { importsToAdd.add(it) }
    }

    private fun collectTypeImport(type: PsiType, importsToAdd: MutableList<String>) {
        when (type) {
            is PsiClassType -> {
                val resolved = type.resolve()
                val qualifiedName = resolved?.qualifiedName
                if (qualifiedName != null && !qualifiedName.startsWith("java.lang.") && qualifiedName.contains(".")) {
                    importsToAdd.add(qualifiedName)
                }
                for (typeArg in type.parameters) {
                    collectTypeImport(typeArg, importsToAdd)
                }
            }
            is PsiArrayType -> {
                collectTypeImport(type.componentType, importsToAdd)
            }
        }
    }

    private fun generateInvokeHandler(
        containingMethod: PsiMethod,
        calledMethod: PsiMethod,
        invokeTarget: String,
        mixinType: InvokeMixinType
    ): String {
        val methodTarget = MixinTargetGenerator.generateInjectTarget(containingMethod)
            .removePrefix("method = \"").removeSuffix("\"")
        val methodName = calledMethod.name
        val handlerName = "${mixinType.annotation.lowercase()}${methodName.replaceFirstChar { it.uppercase() }}"

        return when (mixinType.annotation) {
            "Inject" -> generateInjectInvokeHandler(methodTarget, invokeTarget, handlerName, containingMethod)
            "Redirect" -> generateRedirectInvokeHandler(methodTarget, invokeTarget, handlerName, calledMethod)
            "WrapOperation" -> generateWrapOperationHandler(methodTarget, invokeTarget, handlerName, calledMethod)
            "ModifyReturnValue" -> generateModifyReturnValueInvokeHandler(methodTarget, invokeTarget, handlerName, calledMethod)
            else -> ""
        }
    }

    private fun generateInjectInvokeHandler(
        methodTarget: String,
        invokeTarget: String,
        handlerName: String,
        containingMethod: PsiMethod
    ): String {
        val params = buildString {
            for (param in containingMethod.parameterList.parameters) {
                append("${param.type.presentableText} ${param.name}, ")
            }
            append("CallbackInfo ci")
        }

        return """@Inject(method = "$methodTarget", at = @At(value = "INVOKE", target = "$invokeTarget"))
private void $handlerName($params) {
}
"""
    }

    private fun generateRedirectInvokeHandler(
        methodTarget: String,
        invokeTarget: String,
        handlerName: String,
        calledMethod: PsiMethod
    ): String {
        val returnType = calledMethod.returnType?.presentableText ?: "void"
        val calledClass = calledMethod.containingClass
        val isStatic = calledMethod.hasModifierProperty("static")

        val params = buildString {
            if (!isStatic && calledClass != null) {
                append("${calledClass.name} instance")
                if (calledMethod.parameterList.parameters.isNotEmpty()) {
                    append(", ")
                }
            }
            append(calledMethod.parameterList.parameters.joinToString(", ") {
                "${it.type.presentableText} ${it.name}"
            })
        }

        val methodCallArgs = calledMethod.parameterList.parameters.joinToString(", ") { it.name }
        val returnStatement = if (returnType == "void") {
            if (isStatic) {
                "${calledClass?.name}.${calledMethod.name}($methodCallArgs);"
            } else {
                "instance.${calledMethod.name}($methodCallArgs);"
            }
        } else {
            if (isStatic) {
                "return ${calledClass?.name}.${calledMethod.name}($methodCallArgs);"
            } else {
                "return instance.${calledMethod.name}($methodCallArgs);"
            }
        }

        return """@Redirect(method = "$methodTarget", at = @At(value = "INVOKE", target = "$invokeTarget"))
private $returnType $handlerName($params) {
    $returnStatement
}
"""
    }

    private fun generateWrapOperationHandler(
        methodTarget: String,
        invokeTarget: String,
        handlerName: String,
        calledMethod: PsiMethod
    ): String {
        val returnType = calledMethod.returnType?.presentableText ?: "void"
        val isVoid = calledMethod.returnType == null || calledMethod.returnType == PsiTypes.voidType()
        val operationType = if (isVoid) "Void" else returnType
        val calledClass = calledMethod.containingClass
        val isStatic = calledMethod.hasModifierProperty("static")

        val params = buildString {
            if (!isStatic && calledClass != null) {
                append("${calledClass.name} instance, ")
            }
            for (param in calledMethod.parameterList.parameters) {
                append("${param.type.presentableText} ${param.name}, ")
            }
            append("Operation<$operationType> original")
        }

        val callArgs = buildList {
            if (!isStatic) add("instance")
            addAll(calledMethod.parameterList.parameters.map { it.name })
        }.joinToString(", ")

        val returnStatement = if (isVoid) {
            "original.call($callArgs);"
        } else {
            "return original.call($callArgs);"
        }

        return """@WrapOperation(method = "$methodTarget", at = @At(value = "INVOKE", target = "$invokeTarget"))
private $returnType $handlerName($params) {
    $returnStatement
}
"""
    }

    private fun generateModifyReturnValueInvokeHandler(
        methodTarget: String,
        invokeTarget: String,
        handlerName: String,
        calledMethod: PsiMethod
    ): String {
        val returnType = calledMethod.returnType?.presentableText ?: "Object"

        return """@ModifyReturnValue(method = "$methodTarget", at = @At(value = "INVOKE", target = "$invokeTarget"))
private $returnType $handlerName($returnType original) {
    return original;
}
"""
    }

    data class InvokeMixinType(
        val displayName: String,
        val annotation: String
    )
}

