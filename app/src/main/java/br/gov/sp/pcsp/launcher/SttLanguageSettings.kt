package br.gov.sp.pcsp.launcher

/** Regras de seleção de idioma por provedor STT (Deepgram, AssemblyAI, ElevenLabs).
 *
 * Cada provedor tem parâmetros e formatos próprios; este objeto concentra a
 * validação e a tradução da escolha persistida nos parâmetros reais de cada
 * requisição (REST e WebSocket), evitando vazamento de parâmetros de um
 * provedor para outro.
 */
object SttLanguageSettings {

    // Lista EXATA de códigos aceitos pelo Nova 3 (REST e WS usam o mesmo valor).
    val DEEPGRAM_CODES: Set<String> = setOf(
        "ar", "ar-AE", "ar-DZ", "ar-EG", "ar-IQ", "ar-IR", "ar-JO", "ar-KW", "ar-LB", "ar-MA",
        "ar-PS", "ar-QA", "ar-SA", "ar-SD", "ar-SY", "ar-TD", "ar-TN", "de", "de-CH", "en",
        "en-AU", "en-GB", "en-IN", "en-NZ", "en-US", "es", "es-419", "fr", "fr-CA", "hi", "it",
        "ja", "ko", "ko-KR", "nl", "nl-BE", "pt", "pt-BR", "pt-PT", "ru", "zh", "zh-CN",
        "zh-Hans", "zh-Hant", "zh-HK", "zh-TW"
    )

    // Universal-3.5 Pro: 18 idiomas.
    val ASSEMBLYAI_CODES: Set<String> = setOf(
        "ar", "da", "de", "en", "es", "fi", "fr", "he", "hi", "it", "ja", "nl", "no", "pt",
        "sv", "tr", "vi", "zh"
    )

    // Scribe v2: códigos de 2 letras exibidos no botão "?" (lista exata).
    val ELEVENLABS_CODES_2: Set<String> = setOf(
        "af", "am", "ar", "as", "az", "be", "bg", "bn", "bs", "ca", "cs", "cy", "da", "de",
        "el", "en", "es", "et", "fa", "ff", "fi", "fr", "ga", "gl", "gu", "ha", "he", "hi",
        "hr", "hu", "hy", "id", "ig", "is", "it", "ja", "jv", "ka", "kk", "km", "kn", "ko",
        "ku", "ky", "lb", "lg", "ln", "lo", "lt", "lv", "mi", "mk", "ml", "mn", "mr", "ms",
        "mt", "my", "ne", "nl", "no", "ny", "oc", "or", "pa", "pl", "ps", "pt", "ro", "ru",
        "sd", "sk", "sl", "sn", "so", "sr", "sv", "sw", "ta", "te", "tg", "th", "tr", "uk",
        "ur", "uz", "vi", "wo", "xh", "zh", "zu"
    )

    // Scribe v2: códigos ISO-639-3 aceitos quando digitados manualmente
    // (não aparecem na lista visual do botão "?").
    val ELEVENLABS_CODES_3: Set<String> = setOf(
        "afr", "amh", "ara", "asm", "ast", "aze", "bel", "ben", "bos", "bul", "cat", "ces",
        "cmn", "cym", "dan", "deu", "ell", "eng", "est", "fas", "fin", "fra", "gle", "glg",
        "guj", "hau", "heb", "hin", "hrv", "hun", "ibo", "ind", "isl", "ita", "jav", "jpn",
        "kat", "kaz", "khm", "kir", "kor", "kur", "lao", "lav", "lit", "ltz", "lug", "mar",
        "mkd", "mlt", "mon", "mri", "msa", "mya", "nep", "nld", "nor", "nso", "oci", "ori",
        "pan", "pol", "por", "pus", "ron", "rus", "snd", "sna", "som", "spa", "srp", "slk",
        "slv", "swa", "swe", "tam", "tel", "tgk", "tha", "tur", "ukr", "urd", "uzb", "vie",
        "wol", "xho", "yor", "zul"
    )

    /** Normaliza a entrada do usuário: " en ,  es , pt " -> listOf("en", "es", "pt"). */
    fun parseCodes(raw: String): List<String> =
        raw.split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    fun isValidDeepgram(code: String): Boolean = code in DEEPGRAM_CODES

    fun isValidAssemblyai(code: String): Boolean = code in ASSEMBLYAI_CODES

    fun isValidElevenlabs(code: String): Boolean =
        code in ELEVENLABS_CODES_2 || code in ELEVENLABS_CODES_3

    /** Códigos inválidos para o provedor (lista vazia = tudo válido). */
    fun invalidCodes(provider: String, codes: List<String>): List<String> = codes.filter { code ->
        when (provider) {
            "deepgram" -> !isValidDeepgram(code)
            "assemblyai" -> !isValidAssemblyai(code)
            "elevenlabs" -> !isValidElevenlabs(code)
            else -> false
        }
    }

    // ---------------- Deepgram: language=<valor> em REST e WS ----------------

    fun deepgramLanguageParam(): String {
        val mode = GrokApiSettings.deepgramLanguageMode()
        return if (mode == "custom") GrokApiSettings.deepgramCustomLanguage().trim() else mode
    }

    // ---------------- AssemblyAI: language_code (REST) / language_codes (WS) ----------------

    /** REST: (language_detection, language_code). detection=true => omitir language_code. */
    fun assemblyaiRestLanguage(): Pair<Boolean, String?> {
        val mode = GrokApiSettings.assemblyaiLanguageMode()
        return when {
            mode == "multi" -> true to null
            mode == "custom" -> {
                val codes = parseCodes(GrokApiSettings.assemblyaiCustomLanguage())
                if (codes.size >= 2) true to null else false to codes.firstOrNull()
            }
            else -> false to mode
        }
    }

    /** WS: lista de códigos para language_codes (vazia = omitir o parâmetro = multi). */
    fun assemblyaiWsLanguageCodes(): List<String> {
        val mode = GrokApiSettings.assemblyaiLanguageMode()
        return when {
            mode == "multi" -> emptyList()
            mode == "custom" -> parseCodes(GrokApiSettings.assemblyaiCustomLanguage())
            else -> listOf(mode)
        }
    }

    // ---------------- ElevenLabs: language_code (+ secondary_languages no WS) ----------------

    /** REST: language_code ou null (multi e custom com vários omitem). */
    fun elevenlabsRestLanguageCode(): String? {
        val mode = GrokApiSettings.elevenlabsLanguageMode()
        return when {
            mode == "multi" -> null
            mode == "custom" -> parseCodes(GrokApiSettings.elevenlabsCustomLanguage())
                .takeIf { it.size == 1 }?.first()
            else -> mode
        }
    }

    /** WS: (language_code, secondary_languages). O primeiro código digitado é o principal. */
    fun elevenlabsWsLanguage(): Pair<String?, List<String>> {
        val mode = GrokApiSettings.elevenlabsLanguageMode()
        return when {
            mode == "multi" -> null to emptyList()
            mode == "custom" -> {
                val codes = parseCodes(GrokApiSettings.elevenlabsCustomLanguage())
                if (codes.isEmpty()) null to emptyList() else codes.first() to codes.drop(1)
            }
            else -> mode to emptyList()
        }
    }
}
