package br.gov.sp.pcsp.whisperlab

/**
 * R6/fase E (E2) — SONDA de UTF-8 na fronteira JNI (SOMENTE LABORATÓRIO).
 *
 * Os dois methods natives existem APENAS na biblioteca DIAG (build
 * -PsigWhisperDiag=true; símbolos Java_br_gov_sp_pcsp_whisperlab_LabUtf8Probe_*).
 * A lib de produção NORMAL não exporta nenhum deles — por isso o cenário s15 do
 * app whisperlab só passa com a lib DIAG no sandbox do lab.
 *
 * [sanitize] percorre o caminho REAL de produção: bytes crus do "modelo" ->
 * new_string_utf8_sanitized (sig_mutf8::sanitize_utf8_for_jni + NewStringUTF).
 * [mutf8Back] devolve o MUTF-8 que o ART emite em GetStringUTFChars (CESU-8
 * para fora do BMP; C0 80 para U+0000) num JSON ASCII.
 */
object LabUtf8Probe {
    external fun sanitize(raw: ByteArray): String
    external fun mutf8Back(text: String): String
}
