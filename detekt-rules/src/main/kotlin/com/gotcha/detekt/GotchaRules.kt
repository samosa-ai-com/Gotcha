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

/** Registers the repo's custom detekt rules. */
class GotchaRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "gotcha"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(NoRawDebugLog(config))
    )
}
