package com.gotcha.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Blocks raw `android.util.Log.d` / `Log.v` calls. Those ship into release builds
 * (`isMinifyEnabled = false`, so R8 keeps them) and always build their message
 * string, even when nothing consumes the output. Debug-level logging must go
 * through `com.gotcha.util.GotchaLog`, whose `d`/`v` are compile-time no-ops in
 * release.
 */
class NoRawDebugLog(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "NoRawDebugLog",
        severity = Severity.Security,
        description = "Raw Log.d/Log.v ships in release builds and always builds its " +
            "message string. Use com.gotcha.util.GotchaLog.d/v, which is a compile-time " +
            "no-op in release.",
        debt = Debt.FIVE_MINS
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val method = expression.calleeExpression?.text
        if (method != "d" && method != "v") return
        val qualified = expression.parent as? KtDotQualifiedExpression ?: return
        val receiver = qualified.receiverExpression.text
        if (receiver != "Log" && receiver != "android.util.Log") return
        report(
            CodeSmell(
                issue,
                Entity.from(qualified),
                "Use GotchaLog.$method instead of raw android.util.Log.$method."
            )
        )
    }
}

/**
 * Blocks the raw popups — `AlertDialog`, `DropdownMenu`, `ExposedDropdownMenu`,
 * and the free-form `Dialog` / `BasicAlertDialog` / `Popup` — outside the theme
 * package. Material 3 1.2 paints the first three from the `surface` role, and a
 * bare `Surface` inside the others defaults to it too. Every glass skin (Aura,
 * Vellum, Orchid, Nocturne) leaves that role translucent, so the screen
 * underneath reads straight through the popup. `SkinAlertDialog`,
 * `SkinDropdownMenu`, `SkinExposedDropdownMenu` and `SkinDialog` hand them an
 * opaque container instead.
 *
 * Every screen was moved to the wrappers once; new screens kept reaching for
 * the Material originals, and nothing looks wrong on the default skin.
 */
class UnskinnedPopup(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "UnskinnedPopup",
        severity = Severity.Defect,
        description = "Material 3 dialogs and menus are see-through on glass skins. " +
            "Use SkinAlertDialog / SkinDialog / SkinDropdownMenu / SkinExposedDropdownMenu from com.gotcha.ui.theme.",
        debt = Debt.FIVE_MINS
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeExpression?.text ?: return
        val wrapper = SKINNED[name] ?: return
        report(CodeSmell(issue, Entity.from(expression), "Use $wrapper instead of $name."))
    }

    private companion object {
        /** Each raw popup, and the skin-aware wrapper that replaces it. */
        val SKINNED = mapOf(
            "AlertDialog" to "SkinAlertDialog",
            "DropdownMenu" to "SkinDropdownMenu",
            "ExposedDropdownMenu" to "SkinExposedDropdownMenu",
            "Dialog" to "SkinDialog",
            "BasicAlertDialog" to "SkinDialog",
            "Popup" to "SkinDialog or SkinDropdownMenu"
        )
    }
}

/**
 * Blocks `copy(alpha = …)` on the colour-scheme roles that glass skins leave
 * translucent. `copy` replaces the alpha instead of scaling it: harmless on
 * Deep Space's opaque roles, but on Aura it turns a 19% white panel into a 50%
 * one — a pale slab the dim ink beside it disappears into. Use
 * `com.gotcha.ui.theme.fade`, which multiplies.
 */
class GlassRoleAlphaOverride(config: Config) : Rule(config) {
    override val issue = Issue(
        id = "GlassRoleAlphaOverride",
        severity = Severity.Defect,
        description = "copy(alpha = …) replaces a glass skin's translucency instead of scaling it. " +
            "Use .fade(fraction) from com.gotcha.ui.theme.",
        debt = Debt.FIVE_MINS
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != "copy") return
        val args = expression.valueArguments
        val setsAlpha = args.any { it.getArgumentName()?.asName?.asString() == "alpha" } ||
            args.firstOrNull()?.let { !it.isNamed() } == true
        if (!setsAlpha) return
        val qualified = expression.parent as? KtDotQualifiedExpression ?: return
        if (qualified.selectorExpression != expression) return
        val receiver = qualified.receiverExpression
        val role = when (receiver) {
            is KtDotQualifiedExpression -> receiver.selectorExpression?.text
            is KtNameReferenceExpression -> receiver.getReferencedName()
            else -> null
        }
        if (role !in GLASS_ROLES) return
        report(CodeSmell(issue, Entity.from(qualified), "Use $role.fade(…) instead of $role.copy(alpha = …)."))
    }

    private companion object {
        /** The roles Skin.kt leaves translucent; see flattenOnto there. */
        val GLASS_ROLES = setOf("background", "surface", "surfaceVariant", "secondaryContainer")
    }
}

/** Registers the repo's custom detekt rules. */
class GotchaRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "gotcha"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(NoRawDebugLog(config), UnskinnedPopup(config), GlassRoleAlphaOverride(config))
    )
}
