package com.gotcha.agent.skills

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File

object SkillRegistry {
    private val json = Json { ignoreUnknownKeys = true }
    private val skills = mutableListOf<Skill>()

    fun init(context: Context) {
        skills.clear()
        try {
            loadSkillsFromAssets(context, "skills")
        } catch (e: Exception) {
            Log.e("SkillRegistry", "Failed to load skills", e)
        }
    }

    private fun loadSkillsFromAssets(context: Context, path: String) {
        val assetManager = context.assets
        val list = assetManager.list(path) ?: return
        for (item in list) {
            val fullPath = if (path.isEmpty()) item else "$path/$item"
            if (item.endsWith(".json")) {
                try {
                    val text = assetManager.open(fullPath).bufferedReader().use { it.readText() }
                    val skill = json.decodeFromString<Skill>(text)
                    skills.add(skill)
                    Log.d("SkillRegistry", "Loaded skill: ${skill.id}")
                } catch (e: Exception) {
                    Log.e("SkillRegistry", "Error parsing $fullPath", e)
                }
            } else if (!item.contains(".")) {
                // Heuristic for directories: no extension.
                loadSkillsFromAssets(context, fullPath)
            }
        }
    }

    fun getAllSkills(): List<Skill> = skills.toList()

    fun getSkillById(id: String): Skill? = skills.find { it.id == id }

    fun getSkillsForPackage(packageName: String): List<Skill> {
        return skills.filter { it.targetPackageNames.contains(packageName) }
    }

    fun searchSkills(query: String): List<Skill> {
        val q = query.lowercase()
        return skills.filter { skill ->
            skill.id.lowercase().contains(q) ||
                skill.description.lowercase().contains(q) ||
                skill.targetPackageNames.any { it.lowercase().contains(q) }
        }
    }
}
