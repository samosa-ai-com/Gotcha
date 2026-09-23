package com.gotcha.tools

import com.gotcha.agent.skills.SkillRegistry
import com.gotcha.connectors.ConnectorCatalog
import com.gotcha.ui.theme.Skins

/**
 * The skins and connectors every build has, for the `update_gotcha_settings`
 * schema. Skills are left out: they are loaded at runtime, and the schema is not.
 */
fun staticSettingsCatalog(): SettingsCatalog = SettingsCatalog(
    skins = Skins.all.associate { it.id to it.label },
    connectors = ConnectorCatalog.all.associate { it.id to it.displayName },
    skills = emptyMap()
)

/**
 * What [GotchaSettingsUpdate] validates ids against: the static catalog plus the
 * skills loaded right now. Read on every call — a community skill can be
 * imported mid-conversation.
 */
fun liveSettingsCatalog(): SettingsCatalog = staticSettingsCatalog().copy(
    skills = SkillRegistry.getAllSkills().associate { it.id to it.title.ifBlank { it.id } }
)
