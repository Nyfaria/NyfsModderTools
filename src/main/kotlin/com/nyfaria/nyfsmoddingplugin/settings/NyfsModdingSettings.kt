package com.nyfaria.nyfsmoddingplugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "NyfsModderToolsSettings",
    storages = [Storage("NyfsModderTools.xml")]
)
@Service(Service.Level.APP)
class NyfsModdingSettings : PersistentStateComponent<NyfsModdingSettings> {

    var enableProjectTemplate: Boolean = true
    var enableCopyATAW: Boolean = true
    var enableCopyMixinTarget: Boolean = true
    var enableMixinAutocomplete: Boolean = true
    var enableMixinInspections: Boolean = true

    override fun getState(): NyfsModdingSettings = this

    override fun loadState(state: NyfsModdingSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): NyfsModdingSettings {
            return ApplicationManager.getApplication().getService(NyfsModdingSettings::class.java)
        }
    }
}

