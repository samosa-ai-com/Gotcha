package com.gotcha.testsupport

import com.gotcha.i18n.StringLookup

/**
 * A [StringLookup] for tests whose fakes never read the text it would return:
 * each string comes back as its resource id, so nothing needs Robolectric.
 */
object NoStrings : StringLookup {
    override fun get(id: Int, vararg args: Any): String = "#$id"

    override fun quantity(id: Int, count: Int, vararg args: Any): String = "#$id"
}
