package com.gotcha.auth

/**
 * Which Samosa AI models the user's plan may call. The list comes from the
 * server (GET /v1/user/limits), the same one its proxy enforces with a 403, so
 * nothing about plans or model names is hard-coded here.
 */
object ModelAccess {
    /**
     * The server's allow-list as a set, or null for "no restriction": a missing
     * or empty list, or one containing "*", matching the proxy's own rule.
     */
    fun allowList(allowedModels: List<String>?): Set<String>? {
        if (allowedModels.isNullOrEmpty() || "*" in allowedModels) return null
        return allowedModels.toSet()
    }

    /** True when [model] is outside [allowed]. A null list locks nothing. */
    fun isLocked(model: String, allowed: Set<String>?): Boolean =
        allowed != null && model.isNotBlank() && model !in allowed
}
