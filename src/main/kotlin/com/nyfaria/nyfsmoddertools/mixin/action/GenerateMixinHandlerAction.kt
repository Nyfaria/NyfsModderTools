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

class GenerateMixinHandlerAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        if (!NyfsModdingSettings.getInstance().enableGenerateMixin) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val method = getTargetMethod(e)
        val isMinecraft = method != null && isMinecraftMethod(method)
        e.presentation.isEnabledAndVisible = isMinecraft
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val method = getTargetMethod(e) ?: return
        val containingClass = method.containingClass ?: return

        val mixinTypes = listOf(
            MixinType("@Inject (HEAD)", "Inject", "HEAD"),
            MixinType("@Inject (TAIL)", "Inject", "TAIL"),
            MixinType("@Inject (RETURN)", "Inject", "RETURN"),
            MixinType("@WrapMethod", "WrapMethod", null),
            MixinType("@Redirect", "Redirect", null),
            MixinType("@ModifyReturnValue", "ModifyReturnValue", null),
            MixinType("@Overwrite", "Overwrite", null)
        )

        val step = object : BaseListPopupStep<MixinType>("Select Mixin Type", mixinTypes) {
            override fun getTextFor(value: MixinType): String = value.displayName

            override fun onChosen(selectedValue: MixinType, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    generateMixinHandlerAsync(project, method, containingClass, selectedValue)
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

    private fun getTargetMethod(e: AnActionEvent): PsiMethod? {
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

    private fun generateMixinHandlerAsync(
        project: Project,
        targetMethod: PsiMethod,
        targetClass: PsiClass,
        mixinType: MixinType
    ) {
        ReadAction.nonBlocking<MixinGenerationContext> {
            val existingMixin = findExistingMixinForClass(project, targetClass)
            val mixinPackages = if (existingMixin == null) findMixinPackages(project) else emptyList()
            val displayNames = mixinPackages.map { dir ->
                getModuleDisplayName(dir)
            }
            MixinGenerationContext(existingMixin, mixinPackages, displayNames)
        }
        .finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState()) { context ->
            if (context.existingMixin != null) {
                addHandlerToMixin(project, context.existingMixin, targetMethod, mixinType)
            } else if (context.mixinPackages.isEmpty()) {
                showNoMixinPackageError(project)
            } else if (context.mixinPackages.size == 1) {
                createMixinClass(project, context.mixinPackages.first(), targetClass, targetMethod, mixinType)
            } else {
                showMixinLocationPopup(project, context.mixinPackages, context.displayNames, targetClass, targetMethod, mixinType)
            }
        }
        .submit(AppExecutorUtil.getAppExecutorService())
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

    private data class MixinGenerationContext(
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

    private fun findExistingMixinForClass(project: Project, targetClass: PsiClass): PsiClass? {
        val scope = GlobalSearchScope.projectScope(project)
        val mixinAnnotation = JavaPsiFacade.getInstance(project)
            .findClass("org.spongepowered.asm.mixin.Mixin", scope) ?: return null

        val mixinClasses = AnnotatedElementsSearch.searchPsiClasses(mixinAnnotation, scope)

        for (mixinClass in mixinClasses) {
            val annotation = mixinClass.getAnnotation("org.spongepowered.asm.mixin.Mixin") ?: continue
            val targets = getMixinTargets(annotation)
            if (targetClass.qualifiedName in targets) {
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

        val targetsAttr = annotation.findAttributeValue("targets")
        if (targetsAttr != null) {
            extractStringTargets(targetsAttr, targets)
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

    private fun extractStringTargets(expr: PsiElement, targets: MutableSet<String>) {
        when (expr) {
            is PsiLiteralExpression -> {
                (expr.value as? String)?.replace('/', '.')?.let { targets.add(it) }
            }
            else -> expr.children.forEach { extractStringTargets(it, targets) }
        }
    }

    private fun showMixinLocationPopup(
        project: Project,
        mixinPackages: List<PsiDirectory>,
        packageNames: List<String>,
        targetClass: PsiClass,
        targetMethod: PsiMethod,
        mixinType: MixinType
    ) {
        val packageMap = mixinPackages.zip(packageNames).toMap()

        val step = object : BaseListPopupStep<PsiDirectory>("Select Mixin Package", mixinPackages) {
            override fun getTextFor(value: PsiDirectory): String {
                return packageMap[value] ?: value.name
            }

            override fun onChosen(selectedValue: PsiDirectory, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    ApplicationManager.getApplication().invokeLater {
                        createMixinClass(project, selectedValue, targetClass, targetMethod, mixinType)
                    }
                }
                return FINAL_CHOICE
            }
        }

        JBPopupFactory.getInstance().createListPopup(step).showInFocusCenter()
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

    private fun createMixinInDefaultPackage(
        project: Project,
        targetClass: PsiClass,
        targetMethod: PsiMethod,
        mixinType: MixinType
    ) {
        val scope = GlobalSearchScope.projectScope(project)
        val anyClass = JavaPsiFacade.getInstance(project).findClass("java.lang.Object", scope)
        val srcDir = anyClass?.containingFile?.containingDirectory?.parentDirectory

        if (srcDir != null) {
            createMixinClass(project, srcDir, targetClass, targetMethod, mixinType)
        }
    }

    private fun createMixinClass(
        project: Project,
        directory: PsiDirectory,
        targetClass: PsiClass,
        targetMethod: PsiMethod,
        mixinType: MixinType
    ) {
        val className = "${targetClass.name}Mixin"
        val factory = JavaPsiFacade.getElementFactory(project)

        WriteCommandAction.runWriteCommandAction(project) {
            val existingFile = directory.findFile("$className.java")
            if (existingFile != null) {
                val psiFile = PsiManager.getInstance(project).findFile(existingFile.virtualFile) as? PsiJavaFile
                val existingClass = psiFile?.classes?.firstOrNull()
                if (existingClass != null) {
                    addHandlerToMixin(project, existingClass, targetMethod, mixinType)
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
                appendImportsForMixinType(mixinType, targetMethod)
                append("\n")
                append("@Mixin($targetClassName.class)\n")
                append("public class $className {\n")
                append(generateHandlerMethod(targetMethod, mixinType))
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

    private fun addHandlerToMixin(
        project: Project,
        mixinClass: PsiClass,
        targetMethod: PsiMethod,
        mixinType: MixinType
    ) {
        val factory = JavaPsiFacade.getElementFactory(project)

        WriteCommandAction.runWriteCommandAction(project) {
            val handlerCode = generateHandlerMethod(targetMethod, mixinType)
            val method = factory.createMethodFromText(handlerCode, mixinClass)

            val addedMethod = mixinClass.add(method) as PsiMethod

            addImportsForMixinType(project, mixinClass.containingFile as PsiJavaFile, mixinType, targetMethod)

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

    private fun StringBuilder.appendImportsForMixinType(mixinType: MixinType, targetMethod: PsiMethod) {
        when (mixinType.annotation) {
            "Inject" -> {
                append("import org.spongepowered.asm.mixin.injection.Inject;\n")
                append("import org.spongepowered.asm.mixin.injection.At;\n")
                val returnType = targetMethod.returnType
                if (returnType == null || returnType == PsiTypes.voidType()) {
                    append("import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;\n")
                } else {
                    append("import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;\n")
                }
            }
            "WrapMethod" -> {
                append("import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;\n")
                append("import com.llamalad7.mixinextras.injector.wrapoperation.Operation;\n")
            }
            "Redirect" -> {
                append("import org.spongepowered.asm.mixin.injection.Redirect;\n")
                append("import org.spongepowered.asm.mixin.injection.At;\n")
            }
            "ModifyReturnValue" -> {
                append("import com.llamalad7.mixinextras.injector.ModifyReturnValue;\n")
                append("import org.spongepowered.asm.mixin.injection.At;\n")
            }
            "Overwrite" -> {
                append("import org.spongepowered.asm.mixin.Overwrite;\n")
            }
        }

        appendTypeImports(targetMethod)
    }

    private fun StringBuilder.appendTypeImports(targetMethod: PsiMethod) {
        val imports = mutableSetOf<String>()

        for (param in targetMethod.parameterList.parameters) {
            collectTypeImportStrings(param.type, imports)
        }
        targetMethod.returnType?.let { collectTypeImportStrings(it, imports) }
        targetMethod.containingClass?.qualifiedName?.let { imports.add(it) }

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

    private fun addImportsForMixinType(project: Project, file: PsiJavaFile, mixinType: MixinType, targetMethod: PsiMethod) {
        val factory = JavaPsiFacade.getElementFactory(project)
        val importList = file.importList ?: return

        val importsToAdd = mutableListOf<String>()

        when (mixinType.annotation) {
            "Inject" -> {
                importsToAdd.add("org.spongepowered.asm.mixin.injection.Inject")
                importsToAdd.add("org.spongepowered.asm.mixin.injection.At")
                val returnType = targetMethod.returnType
                if (returnType == null || returnType == PsiTypes.voidType()) {
                    importsToAdd.add("org.spongepowered.asm.mixin.injection.callback.CallbackInfo")
                } else {
                    importsToAdd.add("org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable")
                }
            }
            "WrapMethod" -> {
                importsToAdd.add("com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod")
                importsToAdd.add("com.llamalad7.mixinextras.injector.wrapoperation.Operation")
            }
            "Redirect" -> {
                importsToAdd.add("org.spongepowered.asm.mixin.injection.Redirect")
                importsToAdd.add("org.spongepowered.asm.mixin.injection.At")
            }
            "ModifyReturnValue" -> {
                importsToAdd.add("com.llamalad7.mixinextras.injector.ModifyReturnValue")
                importsToAdd.add("org.spongepowered.asm.mixin.injection.At")
            }
            "Overwrite" -> {
                importsToAdd.add("org.spongepowered.asm.mixin.Overwrite")
            }
        }

        collectTypeImports(targetMethod, importsToAdd)

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

    private fun collectTypeImports(targetMethod: PsiMethod, importsToAdd: MutableList<String>) {
        for (param in targetMethod.parameterList.parameters) {
            collectTypeImport(param.type, importsToAdd)
        }
        targetMethod.returnType?.let { collectTypeImport(it, importsToAdd) }
        targetMethod.containingClass?.qualifiedName?.let { importsToAdd.add(it) }
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

    private fun generateHandlerMethod(
        targetMethod: PsiMethod,
        mixinType: MixinType
    ): String {
        val methodTarget = MixinTargetGenerator.generateInjectTarget(targetMethod)
            .removePrefix("method = \"").removeSuffix("\"")
        val methodName = targetMethod.name
        val handlerName = "${mixinType.annotation.lowercase()}${methodName.replaceFirstChar { it.uppercase() }}"

        return when (mixinType.annotation) {
            "Inject" -> generateInjectHandler(targetMethod, methodTarget, handlerName, mixinType.atValue!!)
            "WrapMethod" -> generateWrapMethodHandler(targetMethod, methodTarget, handlerName)
            "Redirect" -> generateRedirectHandler(targetMethod, methodTarget, handlerName)
            "ModifyReturnValue" -> generateModifyReturnValueHandler(targetMethod, methodTarget, handlerName)
            "Overwrite" -> generateOverwriteHandler(targetMethod)
            else -> ""
        }
    }

    private fun generateInjectHandler(
        targetMethod: PsiMethod,
        methodTarget: String,
        handlerName: String,
        atValue: String
    ): String {
        val returnType = targetMethod.returnType
        val isVoid = returnType == null || returnType == PsiTypes.voidType()
        val callbackType = if (isVoid) "CallbackInfo" else "CallbackInfoReturnable<${returnType?.presentableText ?: "?"}>"

        val params = buildString {
            for (param in targetMethod.parameterList.parameters) {
                append("${param.type.presentableText} ${param.name}, ")
            }
            append("$callbackType ci")
        }

        return """@Inject(method = "$methodTarget", at = @At("$atValue"))
private void $handlerName($params) {
}
"""
    }

    private fun generateWrapMethodHandler(
        targetMethod: PsiMethod,
        methodTarget: String,
        handlerName: String
    ): String {
        val returnType = targetMethod.returnType
        val isVoid = returnType == null || returnType == PsiTypes.voidType()
        val returnTypeStr = returnType?.presentableText ?: "void"
        val operationType = if (isVoid) "Void" else returnTypeStr
        val containingClass = targetMethod.containingClass
        val isStatic = targetMethod.hasModifierProperty("static")

        val params = buildString {
            if (!isStatic && containingClass != null) {
                append("${containingClass.name} instance, ")
            }
            for (param in targetMethod.parameterList.parameters) {
                append("${param.type.presentableText} ${param.name}, ")
            }
            append("Operation<$operationType> original")
        }

        val callArgs = buildList {
            if (!isStatic) add("instance")
            addAll(targetMethod.parameterList.parameters.map { it.name })
        }.joinToString(", ")

        val returnStatement = if (isVoid) {
            "original.call($callArgs);"
        } else {
            "return original.call($callArgs);"
        }

        return """@WrapMethod(method = "$methodTarget")
    private $returnTypeStr $handlerName($params) {
        $returnStatement
    }"""
    }

    private fun generateRedirectHandler(
        targetMethod: PsiMethod,
        methodTarget: String,
        handlerName: String
    ): String {
        val returnType = targetMethod.returnType?.presentableText ?: "void"

        return """@Redirect(method = "$methodTarget", at = @At(value = "INVOKE", target = ""))
private $returnType $handlerName() {
}
"""
    }

    private fun generateModifyReturnValueHandler(
        targetMethod: PsiMethod,
        methodTarget: String,
        handlerName: String
    ): String {
        val returnType = targetMethod.returnType?.presentableText ?: "Object"

        return """@ModifyReturnValue(method = "$methodTarget", at = @At("RETURN"))
private $returnType $handlerName($returnType original) {
    return original;
}
"""
    }

    private fun generateOverwriteHandler(
        targetMethod: PsiMethod
    ): String {
        val returnType = targetMethod.returnType?.presentableText ?: "void"
        val params = targetMethod.parameterList.parameters.joinToString(", ") {
            "${it.type.presentableText} ${it.name}"
        }
        val methodName = targetMethod.name

        return """@Overwrite
public $returnType $methodName($params) {
}
"""
    }

    data class MixinType(
        val displayName: String,
        val annotation: String,
        val atValue: String?
    )
}
