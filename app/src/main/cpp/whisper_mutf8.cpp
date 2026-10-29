// R2/fase C — sanitizador Modified UTF-8 para a fronteira JNI.
// Ver contrato e decisões em whisper_mutf8.h. Sem dependência de JNI/Android:
// compila no host (teste Zig com ASan/UBSan).

#include "whisper_mutf8.h"

namespace {

constexpr char kReplacement[] = "\xEF\xBF\xBD"; // U+FFFD em UTF-8
constexpr std::size_t kReplacementLen = 3;

inline bool utf8_is_continuation(unsigned char byte) {
    return (byte & 0xC0) == 0x80;
}

// Quantidade TOTAL de bytes esperada para a sequência iniciada em `lead`
// (1 quando é ASCII); 0 quando `lead` não pode iniciar sequência alguma.
inline std::size_t utf8_sequence_length(unsigned char lead) {
    if (lead < 0x80) return 1;
    if (lead >= 0xC2 && lead <= 0xDF) return 2;         // 2 bytes (C0/C1 ver abaixo)
    if (lead == 0xC0) return 2;                          // usado apenas para C0 80 (NUL MUTF-8)
    if (lead >= 0xE0 && lead <= 0xEF) return 3;
    if (lead >= 0xF0 && lead <= 0xF4) return 4;
    return 0;                                            // 0x80-0xBF, 0xC1, 0xF5-0xFF
}

// A sequência completa de n bytes em p é válida (padrão de continuação +
// faixas mínimas/máximas)? Aceita as duas formas especiais do Modified UTF-8:
// C0 80 (NUL codificado) e ED A0-BF xx (surrogado CESU-8).
bool utf8_sequence_valid(const unsigned char * p, std::size_t n) {
    switch (n) {
        case 1:
            return p[0] < 0x80;
        case 2:
            if (p[0] == 0xC0) return p[1] == 0x80;
            return p[0] >= 0xC2 && p[0] <= 0xDF && utf8_is_continuation(p[1]);
        case 3: {
            if (!utf8_is_continuation(p[2])) return false;
            if (p[0] == 0xE0) return p[1] >= 0xA0 && p[1] <= 0xBF;       // sem overlong
            if (p[0] == 0xED) return p[1] >= 0x80 && p[1] <= 0xBF;       // BMP + surrogado CESU-8
            return utf8_is_continuation(p[1]);                            // E1-EF
        }
        case 4: {
            if (!utf8_is_continuation(p[1]) || !utf8_is_continuation(p[2]) || !utf8_is_continuation(p[3])) {
                return false;
            }
            if (p[0] == 0xF0) return p[1] >= 0x90 && p[1] <= 0xBF;       // sem overlong
            if (p[0] == 0xF4) return p[1] >= 0x80 && p[1] <= 0x8F;       // <= U+10FFFF
            return true;                                                  // F1-F3
        }
        default:
            return false;
    }
}

} // namespace

namespace sig_mutf8 {

bool sanitize_utf8_for_jni(std::string & text, int * replaced, int * replaced_bytes) {
    if (replaced != nullptr) *replaced = 0;
    if (replaced_bytes != nullptr) *replaced_bytes = 0;

    const unsigned char * data = reinterpret_cast<const unsigned char *>(text.data());
    const std::size_t size = text.size();

    std::string out;
    out.reserve(size);

    std::size_t i = 0;
    int count = 0;
    int consumed = 0;
    bool changed = false;

    while (i < size) {
        const unsigned char lead = data[i];

        // NUL cru: substituído por U+FFFD (truncaria a C-string no NewStringUTF).
        if (lead == 0x00) {
            out.append(kReplacement, kReplacementLen);
            ++count;
            ++consumed;
            changed = true;
            ++i;
            continue;
        }

        const std::size_t expected = utf8_sequence_length(lead);
        if (expected == 1) {
            out.push_back(static_cast<char>(lead));
            ++i;
            continue;
        }
        if (expected > 1 && i + expected <= size && utf8_sequence_valid(data + i, expected)) {
            out.append(reinterpret_cast<const char *>(data + i), expected);
            i += expected;
            continue;
        }

        // Inválida: byte inicial ilegal, continuação errada, overlong, ou
        // truncada/interrompida. Consome o byte inicial + as continuações que
        // existirem (até o tamanho esperado), sem engolir o próximo byte útil.
        std::size_t span = 1;
        if (expected > 1) {
            while (span < expected && i + span < size && utf8_is_continuation(data[i + span])) {
                ++span;
            }
        }
        out.append(kReplacement, kReplacementLen);
        ++count;
        consumed += static_cast<int>(span);
        changed = true;
        i += span;
    }

    if (!changed) {
        return false;
    }

    text.swap(out);
    if (replaced != nullptr) *replaced = count;
    if (replaced_bytes != nullptr) *replaced_bytes = consumed;
    return true;
}

} // namespace sig_mutf8
