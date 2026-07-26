package com.gotcha.i18n

/**
 * Hardcoded spoken/displayed strings, translated per [Language]. Per design decision D3:
 * static translated text is O(1), zero latency, zero assets — chosen over pre-recorded
 * audio (breaks voice consistency) or switching TTS engines mid-turn.
 *
 * Turn-start fillers are a reduced core set of 6 (not the full historical 15) — six
 * weighted variants is ample perceived variety for a filler that plays once per turn.
 */
object SpokenPhrases {

    /** Weighted turn-start fillers. Falls back to English for unlisted languages. */
    fun turnStart(lang: Language): List<Pair<String, Int>> =
        turnStartPhrases[lang] ?: turnStartPhrases.getValue(Language.ENGLISH)

    fun callStarted(lang: Language): String = callStartedPhrases[lang] ?: callStartedPhrases.getValue(Language.ENGLISH)

    fun confirmationNeeded(lang: Language): String =
        confirmationNeededPhrases[lang] ?: confirmationNeededPhrases.getValue(Language.ENGLISH)

    private val turnStartPhrases: Map<Language, List<Pair<String, Int>>> = mapOf(
        Language.ENGLISH to listOf(
            "Gotcha" to 5,
            "Got it" to 2,
            "One moment" to 2,
            "On it" to 1,
            "Let me check" to 1,
            "Okay" to 1
        ),
        Language.SPANISH to listOf(
            "Entendido" to 5,
            "Vale" to 2,
            "Un momento" to 2,
            "Voy" to 1,
            "Déjame revisar" to 1,
            "Bien" to 1
        ),
        Language.FRENCH to listOf(
            "Compris" to 5,
            "D'accord" to 2,
            "Un instant" to 2,
            "J'y vais" to 1,
            "Laisse-moi vérifier" to 1,
            "Bien" to 1
        ),
        Language.GERMAN to listOf(
            "Verstanden" to 5,
            "Alles klar" to 2,
            "Einen Moment" to 2,
            "Bin dran" to 1,
            "Lass mich nachsehen" to 1,
            "Okay" to 1
        ),
        Language.HINDI to listOf(
            "समझ गया" to 5,
            "ठीक है" to 2,
            "एक पल" to 2,
            "कर रहा हूँ" to 1,
            "देखता हूँ" to 1,
            "ठीक" to 1
        ),
        Language.JAPANESE to listOf(
            "了解" to 5,
            "わかりました" to 2,
            "少々お待ちを" to 2,
            "対応します" to 1,
            "確認します" to 1,
            "オーケー" to 1
        ),
        Language.CHINESE to listOf(
            "明白了" to 5,
            "好的" to 2,
            "稍等" to 2,
            "马上处理" to 1,
            "我看看" to 1,
            "好" to 1
        ),
        Language.ITALIAN to listOf(
            "Capito" to 5,
            "Va bene" to 2,
            "Un momento" to 2,
            "Ci penso io" to 1,
            "Fammi controllare" to 1,
            "Okay" to 1
        ),
        Language.PORTUGUESE to listOf(
            "Entendido" to 5,
            "Certo" to 2,
            "Um momento" to 2,
            "Já vou" to 1,
            "Deixa eu verificar" to 1,
            "Ok" to 1
        )
    )

    private val callStartedPhrases: Map<Language, String> = mapOf(
        Language.ENGLISH to "Call started. I'm ready when you are.",
        Language.SPANISH to "Llamada iniciada. Estoy listo cuando quieras.",
        Language.FRENCH to "Appel démarré. Je suis prêt quand vous voulez.",
        Language.GERMAN to "Anruf gestartet. Ich bin bereit, wenn du es bist.",
        Language.HINDI to "कॉल शुरू हो गई है। जब आप तैयार हों, बताइए।",
        Language.JAPANESE to "通話を開始しました。準備ができたら教えてください。",
        Language.CHINESE to "通话已开始,准备好后请告诉我。",
        Language.ITALIAN to "Chiamata iniziata. Sono pronto quando vuoi.",
        Language.PORTUGUESE to "Chamada iniciada. Estou pronto quando você quiser."
    )

    private val confirmationNeededPhrases: Map<Language, String> = mapOf(
        Language.ENGLISH to "I need a confirmation — check the dialog on your screen.",
        Language.SPANISH to "Necesito una confirmación: revisa el cuadro de diálogo en tu pantalla.",
        Language.FRENCH to "J'ai besoin d'une confirmation — vérifiez la boîte de dialogue sur votre écran.",
        Language.GERMAN to "Ich brauche eine Bestätigung — schau dir den Dialog auf deinem Bildschirm an.",
        Language.HINDI to "मुझे पुष्टि चाहिए — अपनी स्क्रीन पर डायलॉग देखें।",
        Language.JAPANESE to "確認が必要です。画面のダイアログを確認してください。",
        Language.CHINESE to "需要确认——请查看屏幕上的对话框。",
        Language.ITALIAN to "Ho bisogno di una conferma: controlla la finestra di dialogo sullo schermo.",
        Language.PORTUGUESE to "Preciso de uma confirmação — verifique a caixa de diálogo na sua tela."
    )
}
