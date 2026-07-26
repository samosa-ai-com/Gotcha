package com.gotcha.agent.skills

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

object SkillRegistry {
    private const val TAG = "SkillRegistry"

    @Suppress("UseCheckOrError")
    private fun requireContext(): Context =
        appContext ?: throw IllegalStateException("SkillRegistry not initialized")

    private val json = Json { ignoreUnknownKeys = true }

    /** Bundled skills (immutable, signed into the APK). */
    private val bundledSkills = mutableListOf<Skill>()

    /** Skills imported from the community — file-backed, mutable. */
    private val communitySkills = mutableListOf<Skill>()

    /** Cached union of [bundledSkills] + [communitySkills], keyed by id. */
    @Volatile
    private var merged: List<Skill> = emptyList()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        reload()
    }

    /** Idempotent bootstrap from any context — safe to call repeatedly. */
    fun bootstrap(context: Context) {
        if (appContext == null) {
            init(context)
        }
    }

    /** Re-read bundled assets and the community store, refreshing the merged view. */
    fun reload() {
        synchronized(this) {
            bundledSkills.clear()
            try {
                appContext?.let { loadSkillsFromAssets(it, "skills") }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load bundled skills", e)
            }
            communitySkills.clear()
            try {
                appContext?.let { ctx ->
                    CommunitySkillStore(ctx).readAll().forEach { (_, skill) ->
                        communitySkills.add(skill)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load community skills", e)
            }
            rebuildMerged()
        }
    }

    private fun rebuildMerged() {
        // Bundled wins on id collision (security: never let a community skill
        // override a built-in skill).
        val byId = linkedMapOf<String, Skill>()
        bundledSkills.forEach { byId[it.id] = it }
        communitySkills.forEach { skill ->
            if (!byId.containsKey(skill.id)) {
                byId[skill.id] = skill
            } else {
                Log.w(TAG, "Community skill id '${skill.id}' is shadowed by a bundled skill")
            }
        }
        merged = byId.values.toList()
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
                    bundledSkills.add(skill)
                    Log.d(TAG, "Loaded bundled skill: ${skill.id}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing $fullPath", e)
                }
            } else if (!item.contains(".")) {
                // Heuristic for directories: no extension.
                loadSkillsFromAssets(context, fullPath)
            }
        }
    }

    fun getAllSkills(): List<Skill> = merged

    /** Returns only community-imported skills (no bundled ones). */
    fun getCommunitySkills(): List<Skill> = communitySkills.toList()

    fun getSkillById(id: String): Skill? = merged.find { it.id == id }

    fun getSkillsForPackage(packageName: String): List<Skill> {
        return merged.filter { skill ->
            skill.targetPackageNames.contains(packageName) ||
                skill.targetPackageNames.contains("*")
        }
    }

    fun searchSkills(query: String): List<Skill> {
        val q = query.lowercase()
        return merged.filter { skill ->
            skill.id.lowercase().contains(q) ||
                skill.description.lowercase().contains(q) ||
                skill.title.lowercase().contains(q) ||
                skill.targetPackageNames.any { it.lowercase().contains(q) }
        }
    }

    /**
     * Fetch a community skill from a URL, validate it, and persist it.
     *
     * The network fetch and the on-disk write both run on [Dispatchers.IO]
     * so callers can invoke this from the main thread without triggering
     * Android's NetworkOnMainThreadException.
     */
    suspend fun importCommunityFromUrl(url: String, allowedHosts: Set<String>): Skill {
        return withContext(Dispatchers.IO) {
            val ctx = requireContext()
            val preview = importerFactory(allowedHosts).fetchPreview(url)
            CommunitySkillStore(ctx).save(preview.rawJson)
            reload()
            preview.skill
        }
    }

    /**
     * Persist a community skill from a raw JSON string. The JSON is parsed
     * and validated on [Dispatchers.IO] so the call is safe from the UI thread.
     * Returns the validated [Skill] — the same instance the registry's merged
     * view will resolve to.
     */
    suspend fun importCommunity(rawJson: String): Skill {
        return withContext(Dispatchers.IO) {
            val skill = SkillImporter.parseAndValidate(rawJson)
            CommunitySkillStore(requireContext()).save(rawJson)
            reload()
            skill
        }
    }

    /** Remove a community skill from disk and reload the registry. Runs on [Dispatchers.IO]. */
    suspend fun removeCommunity(id: String): Boolean {
        return withContext(Dispatchers.IO) {
            val ctx = requireContext()
            val removed = CommunitySkillStore(ctx).remove(id)
            if (removed) reload()
            removed
        }
    }

    @Volatile
    private var importerFactory: (Set<String>) -> SkillImporter = { hosts -> SkillImporter(hosts) }

    @VisibleForTesting
    fun setImporterFactoryForTesting(factory: (Set<String>) -> SkillImporter) {
        importerFactory = factory
    }
}
