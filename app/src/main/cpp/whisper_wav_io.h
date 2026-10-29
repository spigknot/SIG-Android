#pragma once

#include <cstdint>
#include <string>
#include <vector>

// Leitura/escrita de WAV PCM mono 16 kHz s16 na fronteira JNI do Whisper.
//
// Contrato de segurança do parser (W07 + E2/rodada 2):
// - Todo chunk e limitado ao tamanho REAL restante do arquivo E ao tamanho
//   declarado no cabecalho RIFF (o contêiner). Um riff_size que declara mais
//   bytes do que o arquivo tem e erro explicito ("RIFF declara mais bytes que
//   o arquivo"); um chunk cujo corpo passa do fim do contêiner RIFF e erro
//   explicito ("chunk WAV excede o limite RIFF"); um chunk que declara mais
//   bytes do que o arquivo contem e erro explicito ("chunk WAV declara mais
//   bytes que o arquivo"). Bytes depois do fim do contêiner sao ignorados
//   (nao pertencem ao RIFF).
// - Sobra de 1-7 bytes dentro do contêiner (nao forma cabecalho de chunk) e
//   erro explicito ("WAV truncado: cabecalho de chunk incompleto") — antes
//   era tratada como fim normal do scan.
// - Padding RIFF truncado (chunk de tamanho impar sem o byte de alinhamento
//   no contêiner) e erro explicito ("WAV truncado").
// - Todo fread e conferido; EOF parcial e erro explicito.
// - fmt duplicado com campos CONFLITANTES e erro explicito ("chunk fmt
//   duplicado conflitante"); fmt duplicado identico e aceito (idempotente).
// - fmt inconsistente (block_align != channels*bits/8; byte_rate !=
//   sample_rate*block_align) e erro explicito, comparado em 64 bits (sem
//   overflow de contadores).
// - data PCM16 com tamanho declarado IMPAR: o byte incompleto NAO vira
//   amostra; e rejeitado e CONTADO em [ReadStats::partial_sample_bytes] —
//   nunca descarte silencioso. O byte de padding do chunk (fora do tamanho
//   declarado) e consumido apenas como alinhamento e nao entra na contagem.
// - RIFF valido com chunks desconhecidos/padding continua aceito; chunks data
//   multiplos sao concatenados em ordem.
namespace sig_wav {

constexpr int kSampleRate = 16000;

// Contagens do que o parser rejeitou dentro de estrutura aceita (E2/rodada 2).
struct ReadStats {
    // Quantidade de chunks data lidos (concatenados em ordem).
    uint64_t data_chunks = 0;
    // Bytes de amostra PCM16 incompletos (tamanho declarado impar de um chunk
    // data) rejeitados e contados. Nunca descartados em silencio.
    uint64_t partial_sample_bytes = 0;
};

// Le o arquivo e converte as amostras para [-1, 1). Retorna false com
// mensagem em `error` para qualquer entrada invalida ou truncada. `stats`
// (opcional) recebe as contagens do que foi rejeitado sem virar amostra.
bool read_wav_mono_16k(const char * path, std::vector<float> & samples, std::string & error,
                       ReadStats * stats = nullptr);

// Escreve um WAV PCM mono 16 kHz s16. Retorna false com mensagem em `error`.
bool write_wav_mono_16k(const char * path, const std::vector<float> & samples, std::string & error);

} // namespace sig_wav
