#pragma once
// SIG R7/fase F — composição VAD (filtered_samples): lista NORMALIZADA de intervalos
// half-open [start, end) em AMOSTRAS do áudio ORIGINAL como FONTE ÚNICA para tamanho,
// cópia e mapa temporal.
//
// Defeito corrigido (R6-D4, reprodutor n08): a composição antiga (upstream) inseria
// 100 ms de SILÊNCIO DIGITAL entre segmentos e usava `n_samples - 1` como limite, com
// o tamanho calculado numa 1ª passagem e a cópia feita numa 2ª (podendo divergir:
// segmentos vazios/invertidos/fora da faixa eram contados mas não copiados → cauda
// não inicializada ou transbordo) e sem tratar sobreposição entre segmentos próximos
// (o contexto de +samples_overlap podia duplicar áudio e, se o intervalo alcançasse o
// próximo início, duplicava amostras reais).
//
// Política de junção ADOTADA (R8/fase E, variante "margem de junção"): contexto REAL
// de `overlap_samples` SOMENTE NAS JUNÇÕES — cabeça −overlap a partir do 2º intervalo
// (preserva o onset que o Silero costuma cortar) e cauda +overlap até o penúltimo;
// as bordas GLOBAIS do áudio ficam intactas (sem padding artificial no início/fim).
// Silêncio digital de 0,1 s do upstream mantido entre intervalos separados e união de
// intervalos que se tocam/sobrepõem (sem cópia dupla). Evidência R8-E (corpora
// n08/n06/n07novo/d01/d1long, mesma lib/Silero): a variante R7 "margem-z" (margens
// nas DUAS pontas, inclusive bordas globais) corrigia o n08 mas regredia n06/d01/
// d1long (+2,2/+4,4/+5,8 pp vs o original VAD-off); a "margem de junção" corrige o
// n08 (0,0 pp), mantém n07novo/d01 em 0,0 pp e melhora o d1long. A remoção do
// silêncio (candidata da R6) foi medida e NÃO adotada (corrompe o controle n06).
// Decodes limítrofes do PCM composto podem alucinar (junk ESTRUTURAL — UTF-8
// inválido/U+FFFD/controle/uso privado/não-caractere): o gate `text_has_junk`
// (abaixo) é usado por whisper.cpp para o fallback delimitado (re-decode do áudio
// ORIGINAL sem VAD). R9/fase E (E1): o gate é CONSERVADOR — script legítimo
// (CJK/cirílico/etc.) NÃO é junk; ver SUMMARY-E da fase E.
// R10/fase F (F1): a heurística da R9-F (F-TOKEN) promovia MISTURA DE ALFABETOS
// a junk ESTRUTURAL *antes* de o contexto do job ser consultado — um veto
// UNIVERSAL por classe de script, que rejeitava texto UTF-8 VÁLIDO (citação,
// marca, nome, termo técnico: 'COVID-19ワクチン', 'OpenAI研究', 'β-actina',
// 'ГосподинSmith', '東京/Paris' → 30 falsos positivos em 48 casos legítimos).
// Correção R10-F: separar dois conceitos DISTINTOS —
//   * junk ESTRUTURAL  = bytes/estado impossível (UTF-8 inválido/U+FFFD/C0-C1/
//     uso privado/não-caractere/surrogate) → veto UNIVERSAL, re-decode do original;
//   * SUSPEITA LINGUÍSTICA = um TOKEN que mistura >=2 classes de letter
//     (latin/cjk/cirílico/other). É texto UTF-8 VÁLIDO; por si só NUNCA é junk e
//     NUNCA dispara retry. Fica REGISTRADA (telemetria/diagnóstico) para uma
//     decisão contextual que combine tarefa/idioma + evidência/confiança da
//     tentativa — combinação que, MEDIDA, não discrimina o defeito do n06 sem
//     rejeitar nome/citação legítima (ver CHECKPOINT-F).
//
// Regras:
//  1. conversão centissegundos→amostras com arredondamento; clamp em [0, n_samples]
//     (half-open: o índice n_samples-1 entra quando o fim é n_samples);
//  2. contexto real ±overlap_samples; sobreposição → UNIÃO (sem duplicação);
//  3. zeros (silence_samples) inseridos SOMENTE entre intervalos separados;
//  4. o mapa (processed_cs ↔ original_cs) é construído dos MESMOS intervalos finais,
//     conservando coordenadas do ORIGINAL; nas junções com silêncio o mapa interpola
//     entre o fim real do intervalo anterior e o início real do seguinte (sem
//     fabricar tempo de fala fora do áudio);
//  5. invariantes verificados: soma dos tamanhos + zeros == bytes escritos (canário);
//     nenhum intervalo vazio/invertido/fora da faixa entra na lista.
//
// Cabeçalho puro (sem dependências de ggml/whisper): incluído por src/whisper.cpp e
// compilado diretamente pelos testes focais de host (tools/whisper/vad_compose_test.cpp).

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace sig_vad_compose {

// Segmento cru do VAD, em centissegundos (como whisper_vad_segments).
struct raw_segment {
    int64_t start_cs;
    int64_t end_cs;
};

// Intervalo normalizado final, em amostras do áudio ORIGINAL.
struct interval {
    int64_t orig_start;  // primeira amostra copiada do original
    int64_t orig_end;    // amostra final EXCLUSIVA no original
    int64_t comp_start;  // primeira amostra no áudio composto
    int64_t length;      // orig_end - orig_start == comprimento copiado
};

struct mapping_point {
    int64_t processed_cs;  // tempo no áudio composto
    int64_t original_cs;   // tempo correspondente no áudio original
};

inline int64_t cs_to_samples(int64_t cs, int64_t sample_rate) {
    return (int64_t)((cs / 100.0) * sample_rate + 0.5);
}

inline int64_t samples_to_cs(int64_t samples, int64_t sample_rate) {
    return (int64_t)((samples / (double) sample_rate) * 100.0 + 0.5);
}

// Normaliza os segmentos do VAD: descarta vazios/invertidos/fora da faixa, ordena,
// aplica contexto real ±overlap_samples SOMENTE NAS JUNÇÕES (cabeça a partir do 2º
// intervalo; cauda até o penúltimo — bordas globais INTACTAS) e une intervalos que
// se tocam ou sobrepõem.
inline std::vector<interval> normalize_intervals(
        const std::vector<raw_segment> & segments,
                         int64_t   n_samples,
                         int64_t   sample_rate,
                         int64_t   overlap_samples) {
    if (overlap_samples < 0) {
        overlap_samples = 0;
    }
    std::vector<interval> clamped;
    clamped.reserve(segments.size());
    for (const auto & seg : segments) {
        int64_t s = cs_to_samples(seg.start_cs, sample_rate);
        int64_t e = cs_to_samples(seg.end_cs,   sample_rate);
        if (e <= s) continue;               // vazio, invertido ou fora da faixa
        if (s < 0) s = 0;
        if (s > n_samples) s = n_samples;
        if (e < 0) e = 0;
        if (e > n_samples) e = n_samples;
        if (e <= s) continue;
        interval iv;
        iv.orig_start = s;
        iv.orig_end   = e;
        iv.comp_start = 0;
        iv.length     = e - s;
        clamped.push_back(iv);
    }
    std::stable_sort(clamped.begin(), clamped.end(), [](const interval & a, const interval & b) {
        if (a.orig_start != b.orig_start) return a.orig_start < b.orig_start;
        return a.orig_end < b.orig_end;
    });

    // R8/fase E: contexto REAL de `overlap_samples` APENAS nas junções — cabeça
    // (−overlap, preserva o onset da fala seguinte) a partir do 2º intervalo e
    // cauda (+overlap, preserva a cauda da fala anterior) até o penúltimo. O
    // primeiro início e o último fim do ORIGINAL não recebem padding artificial.
    for (size_t k = 0; k < clamped.size(); ++k) {
        if (k > 0) {
            if (clamped[k].orig_start > overlap_samples) {
                clamped[k].orig_start -= overlap_samples;
            } else {
                clamped[k].orig_start = 0;
            }
        }
        if (k + 1 < clamped.size()) {
            const int64_t e = clamped[k].orig_end + overlap_samples;
            clamped[k].orig_end = e > n_samples ? n_samples : e;
        }
        clamped[k].length = clamped[k].orig_end - clamped[k].orig_start;
    }
    // as margens podem reordenar as aberturas (ex.: duplicata com cabeça antes do
    // início do primeiro) — reordena antes da união para manter a monotonicidade.
    std::stable_sort(clamped.begin(), clamped.end(), [](const interval & a, const interval & b) {
        if (a.orig_start != b.orig_start) return a.orig_start < b.orig_start;
        return a.orig_end < b.orig_end;
    });

    // união: intervalos que se tocam/sobrepõem viram um só (sem cópia dupla).
    std::vector<interval> out;
    out.reserve(clamped.size());
    for (const auto & iv : clamped) {
        if (!out.empty() && iv.orig_start <= out.back().orig_end) {
            if (iv.orig_end > out.back().orig_end) {
                out.back().orig_end = iv.orig_end;
                out.back().length   = out.back().orig_end - out.back().orig_start;
            }
        } else {
            out.push_back(iv);
        }
    }
    return out;
}

// Compõe o áudio filtrado e o mapa temporal a partir da MESMA lista normalizada.
// `silence_samples` (zeros) é inserido entre intervalos SEPARADOS; 0 desativa.
// Retorna false se os invariantes de tamanho/faixa falharem (canário contra
// divergência tamanho×cópia).
inline bool compose(
        const float * samples,
              int64_t   n_samples,
              int64_t   sample_rate,
        const std::vector<raw_segment> & segments,
              int64_t   overlap_samples,
              int64_t   silence_samples,
        std::vector<float> & filtered,
        std::vector<interval> & intervals_out,
        std::vector<mapping_point> & mapping_out) {
    std::vector<interval> intervals = normalize_intervals(segments, n_samples, sample_rate, overlap_samples);
    if (silence_samples < 0) {
        silence_samples = 0;
    }

    int64_t total = 0;
    for (size_t k = 0; k < intervals.size(); ++k) {
        intervals[k].comp_start = total;
        intervals[k].length     = intervals[k].orig_end - intervals[k].orig_start;
        total                  += intervals[k].length;
        if (k + 1 < intervals.size()) {
            total += silence_samples;
        }
    }
    if (total < 0) {
        return false;
    }

    filtered.clear();
    filtered.resize((size_t) total);  // pode lançar bad_alloc; o chamador trata

    mapping_out.clear();
    mapping_out.reserve(intervals.size() * 2);

    int64_t offset = 0;
    for (size_t k = 0; k < intervals.size(); ++k) {
        const auto & iv = intervals[k];
        if (iv.length > 0) {
            std::memcpy(filtered.data() + offset, samples + iv.orig_start, (size_t) iv.length * sizeof(float));
        }
        // pontos reais (coordenadas de amostras que existem e foram copiadas); nas
        // junções com silêncio o mapa interpola entre o fim real anterior e o início
        // real seguinte (a interpolação fica dentro do gap REAL do original).
        mapping_out.push_back({ samples_to_cs(iv.comp_start, sample_rate),             samples_to_cs(iv.orig_start, sample_rate) });
        mapping_out.push_back({ samples_to_cs(iv.comp_start + iv.length, sample_rate), samples_to_cs(iv.orig_end,   sample_rate) });
        offset += iv.length;
        if (k + 1 < intervals.size()) {
            if (silence_samples > 0) {
                std::memset(filtered.data() + offset, 0, (size_t) silence_samples * sizeof(float));
            }
            offset += silence_samples;
        }
    }

    if (offset != total) {  // canário: tamanho == bytes escritos
        return false;
    }

    intervals_out = std::move(intervals);
    return true;
}

// Busca no mapa processed→original. Aceita pt duplicado (junção sem silêncio):
// para t == pt devolve o ponto PÓS-junção (último com pt <= t); a interpolação
// linear fica DENTRO de um trecho (ou atravessa o gap real de uma junção com
// silêncio, entre o fim real anterior e o início real seguinte).
inline int64_t map_processed_to_original(int64_t processed_cs, const std::vector<mapping_point> & table) {
    if (table.empty()) {
        return processed_cs;
    }
    if (processed_cs <= table.front().processed_cs) {
        return table.front().original_cs;
    }
    if (processed_cs >= table.back().processed_cs) {
        return table.back().original_cs;
    }

    // primeiro entry com processed_time > t
    auto upper = std::upper_bound(table.begin(), table.end(), processed_cs,
        [](int64_t time, const mapping_point & entry) { return time < entry.processed_cs; });
    auto lower = upper - 1;  // último com processed_time <= t

    if (lower->processed_cs == processed_cs) {
        return lower->original_cs;
    }

    const int64_t processed_diff = upper->processed_cs - lower->processed_cs;
    const int64_t original_diff  = upper->original_cs  - lower->original_cs;
    const int64_t offset         = processed_cs        - lower->processed_cs;
    if (processed_diff == 0) {
        return lower->original_cs;
    }
    return lower->original_cs + (offset * original_diff) / processed_diff;
}

// Verificação de invariantes do mapa (monotonicidade não-decrescente e bounds);
// usada pelos testes focais e como canário defensivo em debug.
inline bool mapping_is_monotonic_and_bounded(const std::vector<mapping_point> & table, int64_t max_original_cs) {
    for (size_t i = 0; i < table.size(); ++i) {
        if (table[i].original_cs < 0 || table[i].original_cs > max_original_cs) return false;
        if (i > 0) {
            if (table[i].processed_cs < table[i - 1].processed_cs) return false;
            if (table[i].original_cs  < table[i - 1].original_cs)  return false;
        }
    }
    return true;
}

// ===========================================================================
// R9/fase E (E1) — GATE DE TEXTO: CRITÉRIO CONSERVADOR CONTEXTUAL.
// ===========================================================================
// Separa FALHA ESTRUTURAL (bytes/estado impossível) de SCRIPT LEGÍTIMO:
//
//   * junk == true SOMENTE por defeito ESTRUTURAL — UTF-8 inválido (byte líder,
//     continuação ou sequência truncada), U+FFFD (substituição), caractere de
//     controle (C0/C1, exceto \t \n \r), área de uso privado, não-caractere e
//     surrogate (fora do repertório válido de texto decodificado);
//   * uma CLASSE Unicode SOZINHA não é prova de alucinação. Fala portuguesa pode
//     CITAR nomes/trechos em japonês/cirílico/inglês; o modo AUTO pode decodificar
//     fala CJK/cirílica; e a task de TRADUÇÃO produz o idioma-alvo (≠ entrada).
//     Logo, CJK / kana / hangul / cirílico NUNCA disparam junk por si sós;
//   * o CONTEXTO (idioma explícito ou detectado + task tradução/transcrição) é
//     usado para CLASSIFICAR e registrar o MOTIVO (telemetria/motivo visível) —
//     nunca para transformar um script legítimo em erro;
//   * nada é transliterado nem apagado; o fallback, quando ocorre, é um
//     RE-DECODE do áudio original (UMA tentativa máxima, motivo registrado).
//
// Corrige o falso-positivo da R8 (que tratava CJK/cirílico como junk e disparava
// retry para texto legítimo: 'こんにちは', 'Привет', 'Hello, 東京'). Ver o
// reprodutor do especialista (ReviewVadPolicy) e SUMMARY-E da fase E.

// Motivo ESTRUTURAL do junk ('' none'' = texto aceitável). Distinto de
// text_suspicion: aqui só entram bytes/estado IMPOSSÍVEIS de texto válido.
enum class text_junk_reason {
    none = 0,
    invalid_utf8,
    replacement_char,
    control_char,
    private_use,
    noncharacter,
    surrogate,
};

// R10/fase F (F1) — SUSPEITA LINGUÍSTICA (NÃO é junk). Um TOKEN que mistura >=2
// classes de letter (ex.: 'выполsche' cirílico+latino) é texto UTF-8 VÁLIDO:
// citação, marca, nome próprio ou termo técnico podem ter esse formato. Por isso
// é apenas REGISTRADA — nunca, sozinha, motivo de re-decode.
enum class text_suspicion {
    none = 0,
    mixed_script_token,  // UM token misturando >=2 classes de letter
};

// Classe de escrita de um ponto de código (APENAS telemetria/classificação; não
// participa da decisão de junk).
enum class text_script_class {
    none = 0,
    latin,
    cjk,           // CJK/radicais/kana/hangul
    cyrillic,
    other_letter,  // grego, árabe, hebraico, tailandês, devanágari, ...
    non_letter,    // pontuação, símbolos, dígitos, espaço
};

// Veredito CONTEXTUAL (por que o texto não-latino NÃO é junk). Nunca altera junk.
enum class text_script_verdict {
    not_applicable = 0,        // texto sem classe de script relevante
    expected_language_script,  // script casa com o idioma explícito do job
    auto_detected_script,      // idioma "auto": script é do idioma detectado
    foreign_citation,          // idioma latino + trecho estrangeiro citado
    translation_output,        // task tradução: idioma-alvo (≠ entrada) esperado
};

struct text_script_profile {
    int latin       = 0;
    int cjk         = 0;
    int cyrillic    = 0;
    int other_letter = 0;
    int non_letter  = 0;
    int chars       = 0;   // pontos de código válidos contados
    bool has_latin() const { return latin > 0; }
    bool has_non_latin_letters() const { return (cjk + cyrillic + other_letter) > 0; }
    bool all_non_latin_letters() const {
        return latin == 0 && (cjk + cyrillic + other_letter) > 0;
    }
};

struct text_gate {
    bool                  junk   = false;
    text_junk_reason      reason = text_junk_reason::none;
    size_t                at_byte = 0;   // byte do primeiro defeito estrutural
    text_script_profile   scripts;
    text_script_verdict   verdict = text_script_verdict::not_applicable;
    // R10/fase F (F1): suspeita linguística REGISTRADA, ortogonal a junk.
    text_suspicion        suspicion = text_suspicion::none;
    size_t                suspicion_at_byte = 0;   // byte do token suspeito
    std::string           suspicion_token;         // o token (para telemetria)
};

inline const char * text_junk_reason_name(text_junk_reason r) {
    switch (r) {
        case text_junk_reason::none:            return "none";
        case text_junk_reason::invalid_utf8:    return "invalid_utf8";
        case text_junk_reason::replacement_char:return "replacement_char";
        case text_junk_reason::control_char:    return "control_char";
        case text_junk_reason::private_use:     return "private_use";
        case text_junk_reason::noncharacter:    return "noncharacter";
        case text_junk_reason::surrogate:       return "surrogate";
    }
    return "unknown";
}

inline const char * text_suspicion_name(text_suspicion s) {
    switch (s) {
        case text_suspicion::none:               return "none";
        case text_suspicion::mixed_script_token: return "mixed_script_token";
    }
    return "unknown";
}

inline const char * text_script_class_name(text_script_class c) {
    switch (c) {
        case text_script_class::none:         return "none";
        case text_script_class::latin:        return "latin";
        case text_script_class::cjk:          return "cjk";
        case text_script_class::cyrillic:     return "cyrillic";
        case text_script_class::other_letter: return "other_letter";
        case text_script_class::non_letter:   return "non_letter";
    }
    return "unknown";
}

inline const char * text_script_verdict_name(text_script_verdict v) {
    switch (v) {
        case text_script_verdict::not_applicable:          return "not_applicable";
        case text_script_verdict::expected_language_script:return "expected_language_script";
        case text_script_verdict::auto_detected_script:    return "auto_detected_script";
        case text_script_verdict::foreign_citation:        return "foreign_citation";
        case text_script_verdict::translation_output:      return "translation_output";
    }
    return "unknown";
}

// Decodifica o próximo ponto de código. Retorna false em sequência inválida
// (byte líder inválido, continuação ausente/inválida, truncada ou > U+10FFFF).
inline bool utf8_decode_next(const std::string & s, size_t & i, uint32_t & cp) {
    const size_t n = s.size();
    if (i >= n) return false;
    const unsigned char c = (unsigned char) s[i];
    size_t len = 0;
    if (c < 0x80) { cp = c; len = 1; }
    else if ((c & 0xE0) == 0xC0) { cp = c & 0x1Fu; len = 2; }
    else if ((c & 0xF0) == 0xE0) { cp = c & 0x0Fu; len = 3; }
    else if ((c & 0xF8) == 0xF0) { cp = c & 0x07u; len = 4; }
    else { return false; }                       // byte líder inválido
    if (i + len > n) { return false; }           // sequência truncada
    for (size_t k = 1; k < len; ++k) {
        const unsigned char cc = (unsigned char) s[i + k];
        if ((cc & 0xC0) != 0x80) { return false; }  // continuação inválida
        cp = (cp << 6) | (uint32_t)(cc & 0x3Fu);
    }
    // rejeita overlong / fora do repertório Unicode
    if (len == 2 && cp < 0x80u) return false;
    if (len == 3 && cp < 0x800u) return false;
    if (len == 4 && (cp < 0x10000u || cp > 0x10FFFFu)) return false;
    i += len;
    return true;
}

// Classifica a CLASSE de escrita de um ponto de código válido (só telemetria).
inline text_script_class classify_script(uint32_t cp) {
    if (cp < 0x80u) {
        if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')) return text_script_class::latin;
        return text_script_class::non_letter;
    }
    if (cp <= 0x2AFu) return text_script_class::latin;                     // Latin-1/Extended-A/B + IPA
    if (cp >= 0x370u && cp <= 0x3FFu) return text_script_class::other_letter; // grego
    if (cp >= 0x400u && cp <= 0x52Fu) return text_script_class::cyrillic;     // cirílico
    if (cp >= 0x531u && cp <= 0x58Fu) return text_script_class::other_letter; // armênio
    if (cp >= 0x590u && cp <= 0x5FFu) return text_script_class::other_letter; // hebraico
    if (cp >= 0x600u && cp <= 0x6FFu) return text_script_class::other_letter; // árabe
    if (cp >= 0x900u && cp <= 0x97Fu) return text_script_class::other_letter; // devanágari
    if (cp >= 0xE00u && cp <= 0xE7Fu) return text_script_class::other_letter; // tailandês
    if ((cp >= 0x3040u && cp <= 0x30FFu) ||   // kana
        (cp >= 0x3400u && cp <= 0x4DBFu) ||   // CJK ext A
        (cp >= 0x4E00u && cp <= 0x9FFFu) ||   // CJK unificado
        (cp >= 0xAC00u && cp <= 0xD7AFu) ||   // hangul sílabas
        (cp >= 0x1100u && cp <= 0x11FFu) ||   // hangul jamo
        (cp >= 0x3130u && cp <= 0x318Fu) ||   // hangul compat
        (cp >= 0xF900u && cp <= 0xFAFFu)) {   // CJK compat
        return text_script_class::cjk;
    }
    if (cp >= 0x1E00u && cp <= 0x1EFFu) return text_script_class::latin;      // Latin Extended Additional
    if (cp >= 0x1F00u && cp <= 0x1FFFu) return text_script_class::other_letter; // grego estendido
    if (cp >= 0x2DE0u && cp <= 0x2DFFu) return text_script_class::cyrillic;   // cirílico est. B (parte)
    if (cp >= 0xA640u && cp <= 0xA69Fu) return text_script_class::cyrillic;   // cirílico est. B
    // demais: pontuação/símbolos < 0x2000 e não-mapeados acima → não-letra
    return text_script_class::non_letter;
}

// R9/fase F (F-TOKEN): espaços de tokenização. ASCII + espaços Unicode comuns
// (o tokenizer do whisper separa por espaço; \s do comparador Python inclui estes).
inline bool is_text_space(uint32_t cp) {
    return cp == 0x20u || cp == 0x09u || cp == 0x0Au || cp == 0x0Du ||          // ASCII
           cp == 0xA0u || (cp >= 0x2000u && cp <= 0x200Au) || cp == 0x2028u ||
           cp == 0x2029u || cp == 0x202Fu || cp == 0x205Fu || cp == 0x3000u || cp == 0xFEFFu;
}

// R9/fase F (F-TOKEN): CRITÉRIO FOCAL do n06 — defeito ESTRUTURAL de TOKEN.
// Um TOKEN (sequência máxima sem espaço) que contém >=2 CLASSES DE LETRA
// distintas (latin / cjk / cirílico / other_letter) é corrupção de decode: o
// tokenizer fundiu scripts incompatíveis num MESMO token (ex.: 'выполsche' =
// cirílico+latino; 'dosадores.'; 'reумião'; 'um츠bois,'; 'Diam市ã'; 'sol寑土').
// NÃO é critério de CLASSE de script (o erro da R8): um token PURO em qualquer
// script ('Привет', 'こんにちは', '日本語', '안녕하세요') NUNCA dispara, e a
// mistura em TOKENS SEPARADOS ('Hello, 東京' -> 2 tokens puros) também não.
// Dígitos/pontuação (non_letter) NÃO contam como classe (ex.: '15h' não é misto).
struct mixed_token_hit {
    bool        mixed   = false;
    size_t      at_byte = 0;  // byte do início do token misto (diagnóstico)
    std::string token;        // o token misto (diagnóstico)
};

inline mixed_token_hit find_mixed_script_token(const std::string & text) {
    mixed_token_hit hit;
    const size_t n = text.size();
    size_t i = 0;
    while (i < n) {
        // pula espaços (tokenização por espaço)
        size_t probe = i;
        uint32_t cp_lead = 0;
        if (utf8_decode_next(text, probe, cp_lead) && is_text_space(cp_lead)) { i = probe; continue; }
        const size_t tok_start = i;
        bool latin = false, cjk = false, cyr = false, other = false;
        while (i < n) {
            uint32_t cp = 0;
            if (!utf8_decode_next(text, i, cp)) { ++i; continue; }  // byte inválido: analyze_text já tratou
            if (is_text_space(cp)) break;
            switch (classify_script(cp)) {
                case text_script_class::latin:        latin = true; break;
                case text_script_class::cjk:          cjk   = true; break;
                case text_script_class::cyrillic:     cyr   = true; break;
                case text_script_class::other_letter: other = true; break;
                case text_script_class::non_letter:
                case text_script_class::none:         break;        // dígitos/pontuação não contam
            }
        }
        const int classes = (int) latin + (int) cjk + (int) cyr + (int) other;
        if (classes >= 2) {
            hit.mixed   = true;
            hit.at_byte = tok_start;
            hit.token   = text.substr(tok_start, i - tok_start);
            return hit;
        }
    }
    return hit;
}

// Análise ESTRUTURAL + PERFIL de script. junk depende SÓ de defeito estrutural.
inline text_gate analyze_text(const std::string & text) {
    text_gate g;
    size_t i = 0;
    const size_t n = text.size();
    while (i < n) {
        const size_t start = i;
        uint32_t cp = 0;
        if (!utf8_decode_next(text, i, cp)) {
            g.junk    = true;
            g.reason  = text_junk_reason::invalid_utf8;
            g.at_byte = start;
            return g;                              // defeito de bytes — para aqui
        }
        if (cp == 0xFFFDu) { g.junk = true; g.reason = text_junk_reason::replacement_char; g.at_byte = start; return g; }
        if (cp == 0x00u || (cp < 0x20u && cp != '\t' && cp != '\n' && cp != '\r') ||
            (cp >= 0x80u && cp <= 0x9Fu)) {        // C0 (exceto \t\n\r) e C1
            g.junk = true; g.reason = text_junk_reason::control_char; g.at_byte = start; return g;
        }
        if (cp >= 0xD800u && cp <= 0xDFFFu) { g.junk = true; g.reason = text_junk_reason::surrogate; g.at_byte = start; return g; }
        if ((cp >= 0xE000u && cp <= 0xF8FFu) ||
            (cp >= 0xF0000u && cp <= 0xFFFFDu) ||
            (cp >= 0x100000u && cp <= 0x10FFFDu)) {
            g.junk = true; g.reason = text_junk_reason::private_use; g.at_byte = start; return g;
        }
        if ((cp >= 0xFDD0u && cp <= 0xFDEFu) ||
            ((cp & 0xFFFEu) == 0xFFFEu)) {          // não-caracteres xFFFE/xFFFF
            g.junk = true; g.reason = text_junk_reason::noncharacter; g.at_byte = start; return g;
        }
        g.scripts.chars++;
        switch (classify_script(cp)) {
            case text_script_class::latin:        g.scripts.latin++;        break;
            case text_script_class::cjk:          g.scripts.cjk++;          break;
            case text_script_class::cyrillic:     g.scripts.cyrillic++;     break;
            case text_script_class::other_letter: g.scripts.other_letter++; break;
            case text_script_class::non_letter:
            case text_script_class::none:         g.scripts.non_letter++;   break;
        }
    }
    // R10/fase F (F1): mistura de alfabetos é texto UTF-8 VÁLIDO — REGISTRADA
    // como SUSPEITA (telemetria), NUNCA como junk. Só chega aqui se NÃO houve
    // defeito estrutural de bytes (o laço acima retorna antes): o defeito de
    // bytes tem PRECEDÊNCIA e mantém o motivo. Nada é transliterado nem apagado.
    if (!g.junk) {
        const mixed_token_hit mixed = find_mixed_script_token(text);
        if (mixed.mixed) {
            g.suspicion           = text_suspicion::mixed_script_token;
            g.suspicion_at_byte   = mixed.at_byte;
            g.suspicion_token     = mixed.token;
        }
    }
    return g;
}

// Back-compat (1 arg): critério estrutural puro. Texto legítimo (latino, acentos,
// pontuação, CJK/kana/hangul/cirílico) retorna false; só defeito estrutural é junk.
inline bool text_has_junk(const std::string & text) {
    return analyze_text(text).junk;
}

// Script esperado para um idioma explícito (código curto). Usado só para CLASSIFICAR.
inline text_script_class script_for_language(const std::string & lang) {
    if (lang.empty() || lang == "auto") return text_script_class::none;
    if (lang == "ru" || lang == "uk" || lang == "bg" || lang == "sr" || lang == "mk" || lang == "be")
        return text_script_class::cyrillic;
    if (lang == "ja" || lang == "zh" || lang == "ko") return text_script_class::cjk;
    if (lang == "ar" || lang == "fa" || lang == "he" || lang == "el" || lang == "th" ||
        lang == "hi" || lang == "ta" || lang == "ka" || lang == "hy")
        return text_script_class::other_letter;
    return text_script_class::latin;   // pt/en/es/fr/de/it/... default latino
}

// CONTEXTUAL: recebe o idioma do job ("auto" = detectado) e a task (tradução).
// Preenche o VEREDITO explicativo; NUNCA transforma script legítimo em junk.
inline text_gate analyze_text_for_job(const std::string & text,
                                      const std::string & language,
                                      bool translate_task) {
    text_gate g = analyze_text(text);
    if (g.junk) {
        return g;  // motivo estrutural manda; contexto é irrelevante para o defeito
    }
    // classifica o MOTIVO de o texto (possivelmente não-latino) NÃO ser junk.
    if (translate_task) {
        g.verdict = text_script_verdict::translation_output;
        return g;
    }
    if (language.empty() || language == "auto") {
        g.verdict = g.scripts.all_non_latin_letters() ? text_script_verdict::auto_detected_script
                                                      : text_script_verdict::not_applicable;
        return g;
    }
    const text_script_class want = script_for_language(language);
    if (want == text_script_class::latin) {
        // idioma latino: trecho não-latino (mesmo misturado a texto latino) é
        // CITAÇÃO estrangeira legítima, não junk.
        g.verdict = g.scripts.has_non_latin_letters() ? text_script_verdict::foreign_citation
                                                      : text_script_verdict::not_applicable;
        return g;
    }
    // idioma não-latino: script casando com o idioma é o esperado.
    const bool matches = (want == text_script_class::cjk && g.scripts.cjk > 0) ||
                         (want == text_script_class::cyrillic && g.scripts.cyrillic > 0) ||
                         (want == text_script_class::other_letter && g.scripts.other_letter > 0);
    g.verdict = matches ? text_script_verdict::expected_language_script
                        : (g.scripts.all_non_latin_letters() ? text_script_verdict::expected_language_script
                                                             : text_script_verdict::not_applicable);
    return g;
}

// ===========================================================================
// R9/fase E (E2) — DECISÃO DE TRANSAÇÃO POR TENTATIVA (pura, testável em host).
// ===========================================================================
// A tentativa 1 (decode do PCM composto) é PROVISÓRIA. O plano abaixo expressa o
// contrato de publicação usado por whisper_full:
//   * rota sem VAD, ERRO ou CANCEL da tentativa 1 → nada é publicado e NÃO há
//     retry (cancel entre tentativas NÃO dispara retry indevido);
//   * tentativa 1 com junk ESTRUTURAL → descarta (não publica) e re-decodifica o
//     áudio original sem VAD (UMA tentativa);
//   * tentativa 1 limpa → publica os segmentos RETIDOS (flush único, sem
//     duplicar/misturar tentativas).
// R10/fase F (F1): a SUSPEITA LINGUÍSTICA (mistura de alfabetos) NÃO entra aqui —
// ela nunca, sozinha, dispara re-decode (é texto UTF-8 válido). Só junk
// ESTRUTURAL rejeita a tentativa.
struct vad_attempt_plan {
    bool publish_attempt1 = false;  // publicar os segmentos retidos da tentativa 1
    bool retry_no_vad     = false;  // descartar a tentativa 1 e re-decodificar sem VAD
};

inline vad_attempt_plan plan_vad_attempt(bool vad_requested, bool attempt1_ok, bool has_junk) {
    vad_attempt_plan p;
    if (!vad_requested || !attempt1_ok) {
        return p;                      // sem VAD / erro / cancel: publica nada, sem retry
    }
    if (has_junk) {
        p.retry_no_vad = true;         // motivo estrutural: 1 retry delimitado
        return p;
    }
    p.publish_attempt1 = true;         // tentativa aceita: flush dos retidos
    return p;
}

// ===========================================================================
// R10/fase F (F2) — CLASSIFICAÇÃO POR RETORNO REAL (tentativa 1 e tentativa 2).
// ===========================================================================
// Defeito R9/E: o log escrevia 'attempt=2 ACCEPTED' SEM verificar o retorno do
// re-decode — um cancel (rc = -100) ou erro (rc != 0) na tentativa 2 era
// reportado como ACEITO (classificação falsa; a métrica não podia ser lida como
// tempo/transcrição). O terminal abaixo deriva SEMPRE do retorno real, com o
// mesmo vocabulário do lado JNI (ok / cancelled / error). Nenhuma semântica de
// cancel é alterada: rc = -100 continua sendo cancelamento.
enum class vad_attempt_terminal {
    none = 0,   // não terminou (rota sem VAD / tentativa não executada)
    accepted,   // rc == 0: decodificou
    cancelled,  // rc == -100: abort_callback interrompeu (cancel)
    failed,     // rc != 0 e != -100: erro de motor (não é transcrição válida)
};

// Retorno de whisper_full quando o abort_callback interrompeu o trabalho
// (mesmo valor/kWhisperFullAborted do JNI).
static constexpr int SIG_VAD_ATTEMPT_ABORTED = -100;

inline vad_attempt_terminal classify_vad_attempt_rc(int rc) {
    if (rc == 0)                      return vad_attempt_terminal::accepted;
    if (rc == SIG_VAD_ATTEMPT_ABORTED) return vad_attempt_terminal::cancelled;
    return vad_attempt_terminal::failed;
}

inline const char * vad_attempt_terminal_name(vad_attempt_terminal t) {
    switch (t) {
        case vad_attempt_terminal::none:      return "NONE";
        case vad_attempt_terminal::accepted:  return "ACCEPTED";
        case vad_attempt_terminal::cancelled: return "CANCELLED";
        case vad_attempt_terminal::failed:    return "FAILED";
    }
    return "UNKNOWN";
}

}  // namespace sig_vad_compose
