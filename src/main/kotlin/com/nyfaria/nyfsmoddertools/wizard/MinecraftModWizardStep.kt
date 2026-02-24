package com.nyfaria.nyfsmoddertools.wizard

import com.intellij.ide.util.projectWizard.ModuleWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class MinecraftModWizardStep(
    @Suppress("unused") private val wizardContext: WizardContext,
    private val builder: MinecraftModuleBuilder
) : ModuleWizardStep() {

    private val propertyGraph = PropertyGraph()
    private val modNameProperty = propertyGraph.property("")
    private val modIdProperty = propertyGraph.property("")
    private val groupProperty = propertyGraph.property("com.example")
    private val versionProperty = propertyGraph.property<MinecraftVersion?>(MinecraftVersion.V1_21_1)
    private val initGitProperty = propertyGraph.property(true)

    private var modNameField: javax.swing.JTextField? = null
    private var modIdField: javax.swing.JTextField? = null

    private val panel = panel {
        row("Mod Name:") {
            textField()
                .bindText(modNameProperty)
                .align(AlignX.FILL)
                .validationOnInput { field ->
                    if (field.text.isBlank()) {
                        ValidationInfo("Mod name cannot be empty", field)
                    } else null
                }
                .also { modNameField = it.component }
                .focused()
        }
        row("Mod ID:") {
            textField()
                .bindText(modIdProperty)
                .align(AlignX.FILL)
                .validationOnInput { field ->
                    when {
                        field.text.isBlank() -> ValidationInfo("Mod ID cannot be empty", field)
                        !field.text.matches(Regex("^[a-z][a-z0-9_]*$")) ->
                            ValidationInfo("Mod ID must start with lowercase letter and contain only lowercase letters, numbers, and underscores", field)
                        else -> null
                    }
                }
                .also { modIdField = it.component }
        }
        row("Group:") {
            textField()
                .bindText(groupProperty)
                .align(AlignX.FILL)
                .validationOnInput { field ->
                    when {
                        field.text.isBlank() -> ValidationInfo("Group cannot be empty", field)
                        !field.text.matches(Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*$")) ->
                            ValidationInfo("Group must be a valid Java package name (e.g., com.example)", field)
                        else -> null
                    }
                }
        }
        row("Minecraft Version:") {
            comboBox(DefaultComboBoxModel(MinecraftVersion.entries.toTypedArray()))
                .bindItem(versionProperty)
                .align(AlignX.FILL)
        }

        separator()

        row {
            checkBox("Initialize Git Repository")
                .bindSelected(initGitProperty)
                .comment("Creates a .git folder so you can easily publish to GitHub")
        }
    }

    init {
        modNameProperty.afterChange { name ->
            if (modIdField?.hasFocus() != true) {
                val generatedId = name.lowercase()
                    .replace(Regex("[^a-z0-9]+"), "_")
                    .replace(Regex("^_+|_+$"), "")
                    .take(32)
                if (generatedId.isNotEmpty() && generatedId.first().isLetter()) {
                    modIdProperty.set(generatedId)
                }
            }
        }
    }

    override fun getComponent(): JComponent = panel

    override fun updateDataModel() {
        builder.modName = modNameProperty.get()
        builder.modId = modIdProperty.get()
        builder.group = groupProperty.get()
        builder.minecraftVersion = versionProperty.get() ?: MinecraftVersion.V1_21_1
        builder.initGit = initGitProperty.get()
    }

    override fun validate(): Boolean {
        val modName = modNameProperty.get()
        val modId = modIdProperty.get()
        val group = groupProperty.get()

        if (modName.isBlank()) return false
        if (modId.isBlank() || !modId.matches(Regex("^[a-z][a-z0-9_]*$"))) return false
        if (group.isBlank() || !group.matches(Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*$"))) return false

        return true
    }
}



