#pragma once

#include <string>

// R2/fase C (fix MUTF-8): o texto devolvido pelo motor (whisper) pode conter
// bytes que NÃO formam Modified UTF-8 — é o caso da saída crua do backend
// Vulkan (TOMBSTONE-vulkan-s5.txt: sequência truncada `0xE8 0x80 <0x20>`).
// `JNIEnv::NewStringUTF` exige Modified UTF-8 e, ao receber bytes inválidos, o
// ART ABORTA o processo (SIGABRT/JNI DETECTED ERROR — não há exceção Java que
// possa ser capturada). Esta função previne o abort: valida a string e troca
// SOMENTE as sequências inválidas por U+FFFD (EF BF BD).
//
// Decisões documentadas:
// - NUL cru (0x00): substituído por U+FFFD. Em C-string o NUL TERMINA a
//   string; sem a troca o texto seria truncado silenciosamente no NewStringUTF.
// - NUL codificado em Modified UTF-8 (0xC0 0x80): preservado (é válido; o ART
//   aceita e o roundtrip JNI do laboratório (s9) comprovou no dispositivo).
// - Pares surrogados CESU-8 (0xED 0xA0-BF xx | 0xED 0xB0-BF xx): preservados —
//   é a forma que o `GetStringUTFChars` do ART emite para caracteres fora do
//   BMP (comprovado no s9).
// - UTF-8 de 4 bytes (F0-F4 com continuações, faixas RFC 3629): preservado — o
//   ART aceita ("We also accept 4 byte UTF sequences in place of encoded
//   surrogate pairs", art/runtime/jni/check_jni.cc, Android 13).
// - Overlong (ex.: E0 80 80), surrogado UTF-8 puro inválido, leads 0x80-0xC1
//   (exceto C0 80), 0xF5-0xFF, sequências truncadas ou interrompidas: viram
//   U+FFFD, consumindo o byte inicial e as continuações que existirem (sem
//   engolir o byte válido seguinte).
//
// Retorna true quando houve substituição. *replaced (obrigatório) recebe o
// número de SEQUÊNCIAS substituídas; *replaced_bytes (opcional) recebe o total
// de bytes ORIGINAIS consumidos pelas substituições.
namespace sig_mutf8 {

bool sanitize_utf8_for_jni(std::string & text, int * replaced, int * replaced_bytes = nullptr);

} // namespace sig_mutf8
