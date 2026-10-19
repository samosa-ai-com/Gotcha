package com.gotcha.detekt

import io.gitlab.arturbosch.detekt.test.TestConfig
import io.gitlab.arturbosch.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Test

class UnskinnedPopupTest {

    private val rule = UnskinnedPopup(TestConfig())

    @Test
    fun `reports raw AlertDialog`() {
        val code = """
            fun f() { AlertDialog(onDismissRequest = {}, confirmButton = {}) }
        """.trimIndent()
        assertEquals(1, rule.lint(code).size)
    }

    @Test
    fun `reports raw DropdownMenu and ExposedDropdownMenu`() {
        val code = """
            fun f() {
                DropdownMenu(expanded = true, onDismissRequest = {}) {}
                ExposedDropdownMenu(expanded = true, onDismissRequest = {}) {}
            }
        """.trimIndent()
        assertEquals(2, rule.lint(code).size)
    }

    @Test
    fun `reports bare and qualified Dialog, BasicAlertDialog and Popup`() {
        val code = """
            fun f() {
                Dialog(onDismissRequest = {}) { Surface {} }
                androidx.compose.ui.window.Dialog(onDismissRequest = {}) { Surface {} }
                BasicAlertDialog(onDismissRequest = {}) {}
                Popup {}
            }
        """.trimIndent()
        assertEquals(4, rule.lint(code).size)
    }

    @Test
    fun `does not report the skin wrappers or the menu box`() {
        val code = """
            fun f() {
                SkinAlertDialog(onDismissRequest = {}, confirmButton = {})
                SkinDropdownMenu(expanded = true, onDismissRequest = {}) {}
                SkinDialog(onDismissRequest = {}) {}
                ExposedDropdownMenuBox(expanded = true, onExpandedChange = {}) {}
            }
        """.trimIndent()
        assertEquals(0, rule.lint(code).size)
    }
}

class GlassRoleAlphaOverrideTest {

    private val rule = GlassRoleAlphaOverride(TestConfig())

    @Test
    fun `reports copy alpha on a glass role`() {
        val code = """
            fun f(scheme: ColorScheme) {
                val a = scheme.surfaceVariant.copy(alpha = 0.5f)
                val b = MaterialTheme.colorScheme.secondaryContainer.copy(0.5f)
                val c = surface.copy(alpha = 0.2f)
            }
        """.trimIndent()
        assertEquals(3, rule.lint(code).size)
    }

    @Test
    fun `does not report opaque roles, fade, or non-alpha copies`() {
        val code = """
            fun f(scheme: ColorScheme) {
                val a = scheme.primary.copy(alpha = 0.18f)
                val b = scheme.surfaceVariant.fade(0.5f)
                val c = scheme.copy(surface = Color.Black)
            }
        """.trimIndent()
        assertEquals(0, rule.lint(code).size)
    }
}
