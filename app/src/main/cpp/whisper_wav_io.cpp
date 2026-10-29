#include "whisper_wav_io.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <memory>
#include <new>

namespace sig_wav {
namespace {

struct FileCloser {
    void operator()(FILE * file) const {
        if (file != nullptr) fclose(file);
    }
};

using FilePtr = std::unique_ptr<FILE, FileCloser>;

bool read_exact(FILE * file, void * destination, size_t bytes) {
    return bytes == 0 || fread(destination, 1, bytes, file) == bytes;
}

bool read_u32_le(FILE * file, uint32_t & out) {
    uint8_t bytes[4];
    if (!read_exact(file, bytes, 4)) return false;
    out = static_cast<uint32_t>(bytes[0]) |
          (static_cast<uint32_t>(bytes[1]) << 8) |
          (static_cast<uint32_t>(bytes[2]) << 16) |
          (static_cast<uint32_t>(bytes[3]) << 24);
    return true;
}

bool read_u16_le(FILE * file, uint16_t & out) {
    uint8_t bytes[2];
    if (!read_exact(file, bytes, 2)) return false;
    out = static_cast<uint16_t>(bytes[0]) | (static_cast<uint16_t>(bytes[1]) << 8);
    return true;
}

// Bytes ainda nao lidos ate `limit` (fim real do arquivo ou fim do contêiner
// RIFF); -1 se a posicao atual estiver fora do limite.
int64_t remaining_to(FILE * file, int64_t limit) {
    const long position = ftell(file);
    if (position < 0 || static_cast<int64_t>(position) > limit) return -1;
    return limit - static_cast<int64_t>(position);
}

// Avanca no maximo ate `limit`; recusa avancos maiores.
bool skip_within(FILE * file, uint64_t bytes, int64_t limit, std::string & error) {
    if (bytes == 0) return true;
    const int64_t remaining = remaining_to(file, limit);
    if (remaining < 0 || bytes > static_cast<uint64_t>(remaining)) {
        error = "WAV truncado";
        return false;
    }
    if (fseek(file, static_cast<long>(bytes), SEEK_CUR) != 0) {
        error = "WAV truncado";
        return false;
    }
    return true;
}

bool truncated(std::string & error) {
    error = "WAV truncado";
    return false;
}

} // namespace

bool read_wav_mono_16k(const char * path, std::vector<float> & samples, std::string & error,
                       ReadStats * stats) {
    samples.clear();
    error.clear();
    if (stats != nullptr) *stats = ReadStats{};
    FilePtr file(fopen(path, "rb"));
    if (!file) {
        error = "não consegui abrir o WAV temporário";
        return false;
    }
    FILE * f = file.get();

    if (fseek(f, 0, SEEK_END) != 0) {
        error = "WAV inválido";
        return false;
    }
    const long file_size_raw = ftell(f);
    if (file_size_raw < 12) {
        error = "WAV inválido";
        return false;
    }
    const int64_t file_size = static_cast<int64_t>(file_size_raw);
    if (fseek(f, 0, SEEK_SET) != 0) {
        error = "WAV inválido";
        return false;
    }

    char riff[4] = {};
    char wave[4] = {};
    uint32_t riff_size = 0;
    if (!read_exact(f, riff, 4) || !read_u32_le(f, riff_size) || !read_exact(f, wave, 4) ||
        strncmp(riff, "RIFF", 4) != 0 || strncmp(wave, "WAVE", 4) != 0) {
        error = "WAV inválido";
        return false;
    }

    // E2: o tamanho RIFF define o contêiner. Declarar mais bytes do que o
    // arquivo tem e erro explicito; o scan para no fim do contêiner e os
    // bytes depois dele (que nao pertencem ao RIFF) sao ignorados.
    const uint64_t container_end_u64 = 8ull + static_cast<uint64_t>(riff_size);
    if (container_end_u64 > static_cast<uint64_t>(file_size)) {
        error = "RIFF declara mais bytes que o arquivo";
        return false;
    }
    const int64_t container_end = static_cast<int64_t>(container_end_u64);

    uint16_t audio_format = 0;
    uint16_t channels = 0;
    uint32_t sample_rate = 0;
    uint32_t byte_rate = 0;
    uint16_t block_align = 0;
    uint16_t bits_per_sample = 0;
    bool saw_fmt = false;
    std::vector<int16_t> pcm;

    for (;;) {
        // E2: sobra de 1-7 bytes no contêiner nao forma cabecalho de chunk —
        // erro explicito em vez de fim normal do scan.
        const int64_t container_remaining = remaining_to(f, container_end);
        if (container_remaining < 0) {
            error = "WAV truncado: contêiner RIFF menor que o cabeçalho";
            return false;
        }
        if (container_remaining == 0) break; // fim limpo do contêiner
        if (container_remaining < 8) {
            error = "WAV truncado: cabeçalho de chunk incompleto";
            return false;
        }

        char chunk_id[4] = {};
        uint32_t chunk_size = 0;
        if (!read_exact(f, chunk_id, 4) || !read_u32_le(f, chunk_size)) return truncated(error);

        // Limite duplo: (a) bytes reais do arquivo; (b) bytes do contêiner.
        const int64_t file_remaining = remaining_to(f, file_size);
        if (file_remaining < 0 ||
            static_cast<uint64_t>(file_remaining) < static_cast<uint64_t>(chunk_size)) {
            error = "chunk WAV declara mais bytes que o arquivo";
            return false;
        }
        const int64_t container_body_remaining = remaining_to(f, container_end);
        if (container_body_remaining < 0 ||
            static_cast<uint64_t>(container_body_remaining) < static_cast<uint64_t>(chunk_size)) {
            error = "chunk WAV excede o limite RIFF";
            return false;
        }

        if (strncmp(chunk_id, "fmt ", 4) == 0) {
            if (chunk_size < 16) {
                error = "chunk fmt truncado";
                return false;
            }
            uint16_t fmt_audio_format = 0;
            uint16_t fmt_channels = 0;
            uint32_t fmt_sample_rate = 0;
            uint32_t fmt_byte_rate = 0;
            uint16_t fmt_block_align = 0;
            uint16_t fmt_bits_per_sample = 0;
            if (!read_u16_le(f, fmt_audio_format) || !read_u16_le(f, fmt_channels) ||
                !read_u32_le(f, fmt_sample_rate) || !read_u32_le(f, fmt_byte_rate) ||
                !read_u16_le(f, fmt_block_align) || !read_u16_le(f, fmt_bits_per_sample)) {
                return truncated(error);
            }
            if (saw_fmt) {
                // E2: duplicata de fmt; identica e idempotente, conflitante e erro.
                if (fmt_audio_format != audio_format || fmt_channels != channels ||
                    fmt_sample_rate != sample_rate || fmt_byte_rate != byte_rate ||
                    fmt_block_align != block_align || fmt_bits_per_sample != bits_per_sample) {
                    error = "chunk fmt duplicado conflitante";
                    return false;
                }
            } else {
                // E2: consistencia interna do fmt PCM, comparada em 64 bits
                // (sem overflow de contadores).
                if (fmt_bits_per_sample % 8u == 0) {
                    const uint64_t bytes_per_frame =
                        static_cast<uint64_t>(fmt_channels) * (fmt_bits_per_sample / 8u);
                    if (static_cast<uint64_t>(fmt_block_align) != bytes_per_frame) {
                        error = "fmt: block_align incompatível";
                        return false;
                    }
                    if (static_cast<uint64_t>(fmt_sample_rate) * fmt_block_align !=
                        static_cast<uint64_t>(fmt_byte_rate)) {
                        error = "fmt: byte_rate incompatível";
                        return false;
                    }
                } else if (fmt_block_align == 0) {
                    error = "fmt: block_align incompatível";
                    return false;
                }
                audio_format = fmt_audio_format;
                channels = fmt_channels;
                sample_rate = fmt_sample_rate;
                byte_rate = fmt_byte_rate;
                block_align = fmt_block_align;
                bits_per_sample = fmt_bits_per_sample;
                saw_fmt = true;
            }
            if (audio_format != 1) {
                error = "WAV temporário não está em PCM";
                return false;
            }
            if (!skip_within(f, static_cast<uint64_t>(chunk_size) - 16, container_end, error)) {
                return false;
            }
        } else if (strncmp(chunk_id, "data", 4) == 0) {
            // chunk_size ja foi comprovado <= bytes reais restantes e <= contêiner.
            const uint64_t data_bytes = static_cast<uint64_t>(chunk_size);
            const size_t whole_samples = static_cast<size_t>(data_bytes / 2);
            const size_t previous = pcm.size();
            if (whole_samples > std::numeric_limits<size_t>::max() - previous) {
                error = "WAV excede o limite de amostras";
                return false;
            }
            if (previous + whole_samples > static_cast<size_t>(std::numeric_limits<int>::max())) {
                error = "WAV excede o limite de amostras";
                return false;
            }
            try {
                pcm.resize(previous + whole_samples);
            } catch (const std::bad_alloc &) {
                error = "sem memória para o WAV temporário";
                return false;
            }
            if (whole_samples > 0 && !read_exact(f, pcm.data() + previous, whole_samples * 2)) {
                return truncated(error);
            }
            if (stats != nullptr) stats->data_chunks += 1;
            if (data_bytes % 2 == 1) {
                // E2: byte incompleto de amostra PCM16 — rejeitado e CONTADO
                // (nunca descartado em silencio); o padding do chunk (fora do
                // tamanho declarado) nao entra nesta contagem.
                uint8_t partial = 0;
                if (!read_exact(f, &partial, 1)) return truncated(error);
                if (stats != nullptr) stats->partial_sample_bytes += 1;
            }
        } else {
            if (!skip_within(f, chunk_size, container_end, error)) return false;
        }

        // Chunks RIFF sao alinhados a bytes pares; o padding precisa existir
        // dentro do contêiner (padding truncado = erro explicito).
        if (chunk_size % 2 == 1) {
            if (!skip_within(f, 1, container_end, error)) return false;
        }
    }

    if (!saw_fmt) {
        error = "WAV sem chunk fmt";
        return false;
    }
    if (channels != 1 || sample_rate != static_cast<uint32_t>(kSampleRate) ||
        bits_per_sample != 16 || pcm.empty()) {
        error = "WAV temporário precisa ser mono, 16 kHz e 16 bits";
        return false;
    }

    try {
        samples.resize(pcm.size());
    } catch (const std::bad_alloc &) {
        error = "sem memória para o WAV temporário";
        return false;
    }
    for (size_t i = 0; i < pcm.size(); ++i) {
        samples[i] = pcm[i] / 32768.0f;
    }
    return true;
}

bool write_wav_mono_16k(const char * path, const std::vector<float> & samples, std::string & error) {
    error.clear();
    FilePtr file(fopen(path, "wb"));
    if (!file) {
        error = "não consegui criar o WAV filtrado";
        return false;
    }
    FILE * f = file.get();

    const uint32_t data_size = static_cast<uint32_t>(samples.size() * sizeof(int16_t));
    const uint32_t riff_size = 36 + data_size;
    const uint16_t format = 1;
    const uint16_t channels = 1;
    const uint32_t sample_rate = static_cast<uint32_t>(kSampleRate);
    const uint32_t byte_rate = sample_rate * channels * sizeof(int16_t);
    const uint16_t block_align = channels * sizeof(int16_t);
    const uint16_t bits_per_sample = 16;
    const uint32_t fmt_size = 16;

    bool ok =
        fwrite("RIFF", 1, 4, f) == 4 &&
        fwrite(&riff_size, sizeof(riff_size), 1, f) == 1 &&
        fwrite("WAVEfmt ", 1, 8, f) == 8 &&
        fwrite(&fmt_size, sizeof(fmt_size), 1, f) == 1 &&
        fwrite(&format, sizeof(format), 1, f) == 1 &&
        fwrite(&channels, sizeof(channels), 1, f) == 1 &&
        fwrite(&sample_rate, sizeof(sample_rate), 1, f) == 1 &&
        fwrite(&byte_rate, sizeof(byte_rate), 1, f) == 1 &&
        fwrite(&block_align, sizeof(block_align), 1, f) == 1 &&
        fwrite(&bits_per_sample, sizeof(bits_per_sample), 1, f) == 1 &&
        fwrite("data", 1, 4, f) == 4 &&
        fwrite(&data_size, sizeof(data_size), 1, f) == 1;
    if (!ok) {
        error = "não consegui criar o WAV filtrado";
        return false;
    }

    for (float value : samples) {
        const float clamped = std::max(-1.0f, std::min(1.0f, value));
        const int16_t pcm_value = static_cast<int16_t>(std::lrintf(clamped * 32767.0f));
        if (fwrite(&pcm_value, sizeof(pcm_value), 1, f) != 1) {
            error = "não consegui criar o WAV filtrado";
            return false;
        }
    }
    return true;
}

} // namespace sig_wav
