package com.nyfaria.nyfsmoddingplugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class NyfsModdingConfigurable : Configurable {

    private var enableProjectTemplateCheckbox: JBCheckBox? = null
    private var enableCopyATAWCheckbox: JBCheckBox? = null
    private var enableCopyMixinTargetCheckbox: JBCheckBox? = null
    private var enableMixinAutocompleteCheckbox: JBCheckBox? = null
    private var enableMixinInspectionsCheckbox: JBCheckBox? = null

    override fun getDisplayName(): String = "Nyf's Modding Plugin"

    override fun createComponent(): JComponent {
        val settings = NyfsModdingSettings.getInstance()

        return panel {
            group("Features") {
                row {
                    enableProjectTemplateCheckbox = checkBox("Enable Minecraft Mod project template")
                        .comment("Shows the Minecraft Mod option in New Project wizard")
                        .component
                    enableProjectTemplateCheckbox?.isSelected = settings.enableProjectTemplate
                }
                row {
                    enableCopyATAWCheckbox = checkBox("Enable Copy AT/AW action")
                        .comment("Adds 'Copy AT/AW' to copy menu for Minecraft classes (Ctrl+Alt+A)")
                        .component
                    enableCopyATAWCheckbox?.isSelected = settings.enableCopyATAW
                }
                row {
                    enableCopyMixinTargetCheckbox = checkBox("Enable Copy Mixin Target action")
                        .comment("Adds 'Copy Mixin Target' to copy menu for Minecraft classes (Ctrl+Alt+M)")
                        .component
                    enableCopyMixinTargetCheckbox?.isSelected = settings.enableCopyMixinTarget
                }
                row {
                    enableMixinAutocompleteCheckbox = checkBox("Enable Mixin autocomplete")
                        .comment("Provides autocomplete for method=\"...\" and @At(target=\"...\") in Mixin annotations")
                        .component
                    enableMixinAutocompleteCheckbox?.isSelected = settings.enableMixinAutocomplete
                }
                row {
                    enableMixinInspectionsCheckbox = checkBox("Enable Mixin inspections")
                        .comment("Shows errors for invalid method= and target= values in Mixin annotations")
                        .component
                    enableMixinInspectionsCheckbox?.isSelected = settings.enableMixinInspections
                }
            }
            group("Note") {
                row {
                    label("Some changes may require restarting the IDE to take effect.")
                }
            }
        }
    }

    override fun isModified(): Boolean {
        val settings = NyfsModdingSettings.getInstance()
        return enableProjectTemplateCheckbox?.isSelected != settings.enableProjectTemplate ||
               enableCopyATAWCheckbox?.isSelected != settings.enableCopyATAW ||
               enableCopyMixinTargetCheckbox?.isSelected != settings.enableCopyMixinTarget ||
               enableMixinAutocompleteCheckbox?.isSelected != settings.enableMixinAutocomplete ||
               enableMixinInspectionsCheckbox?.isSelected != settings.enableMixinInspections
    }

    override fun apply() {
        val settings = NyfsModdingSettings.getInstance()
        settings.enableProjectTemplate = enableProjectTemplateCheckbox?.isSelected ?: true
        settings.enableCopyATAW = enableCopyATAWCheckbox?.isSelected ?: true
        settings.enableCopyMixinTarget = enableCopyMixinTargetCheckbox?.isSelected ?: true
        settings.enableMixinAutocomplete = enableMixinAutocompleteCheckbox?.isSelected ?: true
        settings.enableMixinInspections = enableMixinInspectionsCheckbox?.isSelected ?: true
    }

    override fun reset() {
        val settings = NyfsModdingSettings.getInstance()
        enableProjectTemplateCheckbox?.isSelected = settings.enableProjectTemplate
        enableCopyATAWCheckbox?.isSelected = settings.enableCopyATAW
        enableCopyMixinTargetCheckbox?.isSelected = settings.enableCopyMixinTarget
        enableMixinAutocompleteCheckbox?.isSelected = settings.enableMixinAutocomplete
        enableMixinInspectionsCheckbox?.isSelected = settings.enableMixinInspections
    }

    override fun disposeUIResources() {
        enableProjectTemplateCheckbox = null
        enableCopyATAWCheckbox = null
        enableCopyMixinTargetCheckbox = null
        enableMixinAutocompleteCheckbox = null
        enableMixinInspectionsCheckbox = null
    }
}

