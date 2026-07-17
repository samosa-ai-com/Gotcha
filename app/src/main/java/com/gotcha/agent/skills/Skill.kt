package com.gotcha.agent.skills

import kotlinx.serialization.Serializable

@Serializable
data class Skill(
    val id: String,
    val targetPackageNames: List<String> = emptyList(),
    val instructions: String,
    val description: String = ""
)
