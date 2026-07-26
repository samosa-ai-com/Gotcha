package com.gotcha.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType

/** One editable field in a [TokenConnectorCard]; the card owns its state. */
class TokenFieldState(
    val label: String,
    initial: String,
    val secret: Boolean = false,
    val keyboard: KeyboardType = KeyboardType.Text
) {
    var value by mutableStateOf(initial)
}

@Composable
fun rememberTokenField(
    label: String,
    initial: String,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text
): TokenFieldState = remember { TokenFieldState(label, initial, secret, keyboard) }
