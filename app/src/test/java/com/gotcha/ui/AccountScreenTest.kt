package com.gotcha.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountScreenTest {

    @Test
    fun theDrawerShowsTheNameThenTheEmail() {
        assertEquals("Priya Sharma", accountDisplayName(" Priya Sharma ", "priya@example.com"))
        assertEquals("priya@example.com", accountDisplayName("", "priya@example.com"))
        assertNull(accountDisplayName(" ", ""))
    }

    @Test
    fun initialsComeFromTheNameOrTheEmail() {
        assertEquals("PS", accountInitials("Priya Sharma"))
        assertEquals("PS", accountInitials("priya.sharma@example.com"))
        assertEquals("A", accountInitials("arjun"))
        assertEquals("?", accountInitials(""))
    }
}
