package com.gotcha.detekt

import io.gitlab.arturbosch.detekt.test.TestConfig
import io.gitlab.arturbosch.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Test

class NoRawDebugLogTest {

    private val rule = NoRawDebugLog(TestConfig())

    @Test
    fun `reports Log dot d`() {
        val code = """
            import android.util.Log
            fun f() { Log.d("TAG", "msg") }
        """.trimIndent()
        assertEquals(1, rule.lint(code).size)
    }

    @Test
    fun `reports Log dot v`() {
        val code = """
            import android.util.Log
            fun f() { Log.v("TAG", "msg") }
        """.trimIndent()
        assertEquals(1, rule.lint(code).size)
    }

    @Test
    fun `reports fully qualified android util Log d`() {
        val code = """
            fun f() { android.util.Log.d("TAG", "msg") }
        """.trimIndent()
        assertEquals(1, rule.lint(code).size)
    }

    @Test
    fun `does not report GotchaLog d`() {
        val code = """
            fun f() { GotchaLog.d("TAG") { "msg" } }
        """.trimIndent()
        assertEquals(0, rule.lint(code).size)
    }

    @Test
    fun `does not report Log w or Log e`() {
        val code = """
            import android.util.Log
            fun f() {
                Log.w("TAG", "msg")
                Log.e("TAG", "msg", IllegalStateException())
            }
        """.trimIndent()
        assertEquals(0, rule.lint(code).size)
    }
}
