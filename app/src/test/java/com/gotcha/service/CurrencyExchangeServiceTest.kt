package com.gotcha.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyExchangeServiceTest {

    private fun amount(price: String) = CurrencyExchangeService.parseAmount(price)!!

    @Test
    fun `dot decimals with comma grouping`() {
        assertEquals(123456.89, amount("₹1,23,456.89"), 0.001)
        assertEquals(1234.0, amount("$1,234"), 0.001)
        assertEquals(15.0, amount("$15.00 USD"), 0.001)
    }

    @Test
    fun `comma decimals with space grouping`() {
        assertEquals(1234.56, amount("1 234,56 ₽"), 0.001)
        assertEquals(1234.56, amount("1 234,56 ₽"), 0.001)
        assertEquals(1500.0, amount("1 500 руб."), 0.001)
        assertEquals(1234.56, amount("1.234,56 €"), 0.001)
    }

    @Test
    fun `a dot after Rs is not a decimal point`() {
        assertEquals(500.0, amount("Rs. 500"), 0.001)
        assertEquals(1234567.0, amount("Rs. 12,34,567"), 0.001)
    }

    @Test
    fun `rouble prices resolve to RUB`() {
        assertEquals("RUB", CurrencyExchangeService.extractCurrencyCode("1 500 ₽"))
        assertEquals("RUB", CurrencyExchangeService.extractCurrencyCode("990 рублей"))
        assertEquals("RUB", CurrencyExchangeService.extractCurrencyCode("2500 RUB"))
    }
}
