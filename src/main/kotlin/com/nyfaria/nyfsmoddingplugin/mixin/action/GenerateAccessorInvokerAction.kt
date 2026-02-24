package com.nyfaria.nyfsmoddingplugin.mixin.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
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
import com.nyfaria.nyfsmoddingplugin.settings.NyfsModdingSettings

class GenerateAccessorInvokerAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        if (!NyfsModdingSettings.getInstance().enableGenerateAccessorInvoker) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val element = getTargetElement(e)
        val isValid = when (element) {
            is PsiField -> isMinecraftElement(element) && element.hasModifierProperty(PsiModifier.PRIVATE)
            is PsiMethod -> isMinecraftElement(element) && element.hasModifierProperty(PsiModifier.PRIVATE)
            else -> false
        }
        e.presentation.isEnabledAndVisible = isValid
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val element = getTargetElement(e) ?: return

        when (element) {
            is PsiField -> generateAccessor(project, element, e)
            is PsiMethod -> generateInvoker(project, element)
        }
    }

    private fun getTargetElement(e: AnActionEvent): PsiElement? {
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset)

        val field = PsiTreeUtil.getParentOfType(element, PsiField::class.java)
        if (field != null) return field

        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        if (method != null) return method

        return null
    }

    private fun isMinecraftElement(element: PsiElement): Boolean {
        val qualifiedName = when (element) {
            is PsiField -> element.containingClass?.qualifiedName
            is PsiMethod -> element.containingClass?.qualifiedName
            else -> null
        } ?: return false

        return qualifiedName.startsWith("net.minecraft") || qualifiedName.startsWith("com.mojang")
    }

    private fun generateAccessor(project: Project, field: PsiField, e: AnActionEvent) {
        val containingClass = field.containingClass ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)

        val options = mutableListOf<AccessorOption>()
        options.add(AccessorOption("Getter", false))
        if (!field.hasModifierProperty(PsiModifier.FINAL)) {
            options.add(AccessorOption("Setter", true))
        }
        options.add(AccessorOption("Both (Getter + Setter)", null))

        val step = object : BaseListPopupStep<AccessorOption>("Generate Accessor", options) {
            override fun getTextFor(value: AccessorOption): String = value.name

            override fun onChosen(selectedValue: AccessorOption, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    generateAccessorAsync(project, containingClass, field, selectedValue)
                }
                return FINAL_CHOICE
            }
        }

        if (editor != null) {
            JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(editor)
        }
    }

    private fun generateAccessorAsync(
        project: Project,
        containingClass: PsiClass,
        field: PsiField,
        option: AccessorOption
    ) {
        ReadAction.nonBlocking<AccessorGenerationContext> {
            val existingAccessor = findExistingAccessorInterface(project, containingClass)
            val mixinPackages = if (existingAccessor == null) findMixinPackages(project) else emptyList()
            val displayNames = mixinPackages.map { dir ->
                getModuleDisplayName(dir)
            }
            AccessorGenerationContext(existingAccessor, mixinPackages, displayNames)
        }
        .finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState()) { context ->
            if (context.existingAccessor != null) {
                addAccessorToInterface(project, context.existingAccessor, field, option)
            } else if (context.mixinPackages.isEmpty()) {
                showNoMixinPackageError(project)
            } else if (context.mixinPackages.size == 1) {
                createAccessorInterface(project, context.mixinPackages.first(), containingClass, field, option)
            } else {
                showInterfaceLocationPopup(project, containingClass, field, option, context.mixinPackages, context.displayNames)
            }
        }
        .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun generateInvoker(project: Project, method: PsiMethod) {
        val containingClass = method.containingClass ?: return

        ReadAction.nonBlocking<AccessorGenerationContext> {
            val existingAccessor = findExistingAccessorInterface(project, containingClass)
            val mixinPackages = if (existingAccessor == null) findMixinPackages(project) else emptyList()
            val displayNames = mixinPackages.map { dir ->
                getModuleDisplayName(dir)
            }
            AccessorGenerationContext(existingAccessor, mixinPackages, displayNames)
        }
        .finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState()) { context ->
            if (context.existingAccessor != null) {
                addInvokerToInterface(project, context.existingAccessor, method)
            } else if (context.mixinPackages.isEmpty()) {
                showNoMixinPackageError(project)
            } else if (context.mixinPackages.size == 1) {
                createInvokerInterface(project, context.mixinPackages.first(), containingClass, method)
            } else {
                showInterfaceLocationPopupForInvoker(project, containingClass, method, context.mixinPackages, context.displayNames)
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

    private data class AccessorGenerationContext(
        val existingAccessor: PsiClass?,
        val mixinPackages: List<PsiDirectory>,
        val displayNames: List<String>
    )

    private fun showNoMixinPackageError(project: Project) {
        com.intellij.openapi.ui.Messages.showErrorDialog(
            project,
            "No existing mixin packages found. Please create a mixin class manually first.",
            "Cannot Generate Accessor/Invoker"
        )
    }

    private fun findExistingAccessorInterface(project: Project, targetClass: PsiClass): PsiClass? {
        val scope = GlobalSearchScope.projectScope(project)
        val mixinAnnotation = JavaPsiFacade.getInstance(project)
            .findClass("org.spongepowered.asm.mixin.Mixin", scope) ?: return null

        val mixinClasses = AnnotatedElementsSearch.searchPsiClasses(mixinAnnotation, scope)

        for (mixinClass in mixinClasses) {
            if (!mixinClass.isInterface) continue

            val annotation = mixinClass.getAnnotation("org.spongepowered.asm.mixin.Mixin") ?: continue
            val targets = getMixinTargets(annotation)

            if (targetClass.qualifiedName in targets) {
                val hasAccessorOrInvoker = mixinClass.methods.any { method ->
                    method.annotations.any {
                        val name = it.nameReferenceElement?.referenceName
                        name == "Accessor" || name == "Invoker"
                    }
                }
                if (hasAccessorOrInvoker) {
                    return mixinClass
                }
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

    private fun showInterfaceLocationPopup(
        project: Project,
        targetClass: PsiClass,
        field: PsiField,
        option: AccessorOption,
        mixinPackages: List<PsiDirectory>,
        packageNames: List<String>
    ) {
        val packageMap = mixinPackages.zip(packageNames).toMap()

        val step = object : BaseListPopupStep<PsiDirectory>("Select Package for Accessor Interface", mixinPackages) {
            override fun getTextFor(value: PsiDirectory): String {
                return packageMap[value] ?: value.name
            }

            override fun onChosen(selectedValue: PsiDirectory, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    ApplicationManager.getApplication().invokeLater {
                        createAccessorInterface(project, selectedValue, targetClass, field, option)
                    }
                }
                return FINAL_CHOICE
            }
        }

        JBPopupFactory.getInstance().createListPopup(step).showInFocusCenter()
    }

    private fun showInterfaceLocationPopupForInvoker(
        project: Project,
        targetClass: PsiClass,
        method: PsiMethod,
        mixinPackages: List<PsiDirectory>,
        packageNames: List<String>
    ) {
        val packageMap = mixinPackages.zip(packageNames).toMap()

        val step = object : BaseListPopupStep<PsiDirectory>("Select Package for Invoker Interface", mixinPackages) {
            override fun getTextFor(value: PsiDirectory): String {
                return packageMap[value] ?: value.name
            }

            override fun onChosen(selectedValue: PsiDirectory, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    ApplicationManager.getApplication().invokeLater {
                        createInvokerInterface(project, selectedValue, targetClass, method)
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

    private fun createAccessorInterface(
        project: Project,
        directory: PsiDirectory,
        targetClass: PsiClass,
        field: PsiField,
        option: AccessorOption
    ) {
        val interfaceName = "${targetClass.name}Accessor"

        WriteCommandAction.runWriteCommandAction(project) {
            val existingFile = directory.findFile("$interfaceName.java")
            if (existingFile != null) {
                val psiFile = PsiManager.getInstance(project).findFile(existingFile.virtualFile) as? PsiJavaFile
                val existingInterface = psiFile?.classes?.firstOrNull()
                if (existingInterface != null) {
                    addAccessorToInterface(project, existingInterface, field, option)
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
                append("import org.spongepowered.asm.mixin.gen.Accessor;\n\n")
                append("@Mixin($targetClassName.class)\n")
                append("public interface $interfaceName {\n")
                append(generateAccessorMethods(field, option))
                append("}\n")
            }

            val psiFile = PsiFileFactory.getInstance(project).createFileFromText(
                "$interfaceName.java",
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

    private fun createInvokerInterface(
        project: Project,
        directory: PsiDirectory,
        targetClass: PsiClass,
        method: PsiMethod
    ) {
        val interfaceName = "${targetClass.name}Accessor"

        WriteCommandAction.runWriteCommandAction(project) {
            val existingFile = directory.findFile("$interfaceName.java")
            if (existingFile != null) {
                val psiFile = PsiManager.getInstance(project).findFile(existingFile.virtualFile) as? PsiJavaFile
                val existingInterface = psiFile?.classes?.firstOrNull()
                if (existingInterface != null) {
                    addInvokerToInterface(project, existingInterface, method)
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
                append("import org.spongepowered.asm.mixin.gen.Invoker;\n\n")
                append("@Mixin($targetClassName.class)\n")
                append("public interface $interfaceName {\n")
                append(generateInvokerMethod(method))
                append("}\n")
            }

            val psiFile = PsiFileFactory.getInstance(project).createFileFromText(
                "$interfaceName.java",
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

    private fun addAccessorToInterface(
        project: Project,
        accessorInterface: PsiClass,
        field: PsiField,
        option: AccessorOption
    ) {
        val factory = JavaPsiFacade.getElementFactory(project)

        WriteCommandAction.runWriteCommandAction(project) {
            val methods = generateAccessorMethods(field, option)

            val methodTexts = parseAccessorMethods(methods)
            for (methodText in methodTexts) {
                val method = factory.createMethodFromText(methodText, accessorInterface)
                accessorInterface.add(method)
            }

            addAccessorImport(project, accessorInterface.containingFile as PsiJavaFile)
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(accessorInterface)

            val virtualFile = accessorInterface.containingFile?.virtualFile
            if (virtualFile != null) {
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
            }
        }
    }

    private fun addInvokerToInterface(
        project: Project,
        accessorInterface: PsiClass,
        method: PsiMethod
    ) {
        val factory = JavaPsiFacade.getElementFactory(project)

        WriteCommandAction.runWriteCommandAction(project) {
            val methodText = generateInvokerMethod(method)
            val invokerMethod = factory.createMethodFromText(methodText, accessorInterface)
            accessorInterface.add(invokerMethod)

            addInvokerImport(project, accessorInterface.containingFile as PsiJavaFile)
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(accessorInterface)

            val virtualFile = accessorInterface.containingFile?.virtualFile
            if (virtualFile != null) {
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
            }
        }
    }

    private fun parseAccessorMethods(methods: String): List<String> {
        val result = mutableListOf<String>()
        val lines = methods.lines()
        var currentMethod = StringBuilder()
        var inMethod = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("@Accessor")) {
                if (inMethod && currentMethod.isNotEmpty()) {
                    result.add(currentMethod.toString().trim())
                }
                currentMethod = StringBuilder()
                currentMethod.append(trimmed).append("\n")
                inMethod = true
            } else if (inMethod && trimmed.isNotEmpty()) {
                currentMethod.append(trimmed).append("\n")
                if (trimmed.endsWith(";")) {
                    result.add(currentMethod.toString().trim())
                    currentMethod = StringBuilder()
                    inMethod = false
                }
            }
        }

        if (inMethod && currentMethod.isNotEmpty()) {
            result.add(currentMethod.toString().trim())
        }

        return result
    }

    private fun addAccessorImport(project: Project, file: PsiJavaFile) {
        val factory = JavaPsiFacade.getElementFactory(project)
        val importList = file.importList ?: return
        val accessorClass = JavaPsiFacade.getInstance(project)
            .findClass("org.spongepowered.asm.mixin.gen.Accessor", GlobalSearchScope.allScope(project))

        if (accessorClass != null) {
            val hasImport = importList.importStatements.any { it.qualifiedName == "org.spongepowered.asm.mixin.gen.Accessor" }
            if (!hasImport) {
                importList.add(factory.createImportStatement(accessorClass))
            }
        }
    }

    private fun addInvokerImport(project: Project, file: PsiJavaFile) {
        val factory = JavaPsiFacade.getElementFactory(project)
        val importList = file.importList ?: return
        val invokerClass = JavaPsiFacade.getInstance(project)
            .findClass("org.spongepowered.asm.mixin.gen.Invoker", GlobalSearchScope.allScope(project))

        if (invokerClass != null) {
            val hasImport = importList.importStatements.any { it.qualifiedName == "org.spongepowered.asm.mixin.gen.Invoker" }
            if (!hasImport) {
                importList.add(factory.createImportStatement(invokerClass))
            }
        }
    }

    private fun generateAccessorMethods(field: PsiField, option: AccessorOption): String {
        val fieldName = field.name
        val fieldType = field.type.presentableText
        val capitalizedName = fieldName.replaceFirstChar { it.uppercase() }

        return buildString {
            if (option.isSetter == null || option.isSetter == false) {
                append("    @Accessor(\"$fieldName\")\n")
                append("    ${fieldType} get$capitalizedName();\n\n")
            }
            if (option.isSetter == null || option.isSetter == true) {
                if (!field.hasModifierProperty(PsiModifier.FINAL)) {
                    append("    @Accessor(\"$fieldName\")\n")
                    append("    void set$capitalizedName($fieldType value);\n")
                }
            }
        }
    }

    private fun generateInvokerMethod(method: PsiMethod): String {
        val methodName = method.name
        val returnType = method.returnType?.presentableText ?: "void"
        val params = method.parameterList.parameters.joinToString(", ") {
            "${it.type.presentableText} ${it.name}"
        }
        val invokerName = "invoke${methodName.replaceFirstChar { it.uppercase() }}"

        return """
    @Invoker("$methodName")
    $returnType $invokerName($params);
"""
    }

    data class AccessorOption(
        val name: String,
        val isSetter: Boolean?
    )
}


