// R28/A4 — caminho de escrita do capturador, com FileOps INJETÁVEL.
//
// Por que existe: o writer real precisa ser testável no host sem tocar no
// aparelho. As operações de arquivo são um conjunto de ponteiros com default
// libc; o teste injeta falhas em cada etapa (abrir, cabeçalho, payload, flush,
// close, rename) e exige que o resultado seja 0 — nunca "sucesso" com dado
// perdido.
//
// Regras implementadas aqui:
//   * payload em <nome>.bin.tmp e publicação só por rename (nunca parcial);
//   * cabeçalho, payload, flush, close e rename verificados;
//   * qualquer falha remove o temporário e devolve 0.
#ifndef SIG_DUMP_FILEOPS_H
#define SIG_DUMP_FILEOPS_H

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <string>

namespace sig_dump_io {

struct FileOps {
    FILE * (*fopen)(const char *, const char *) = ::fopen;
    std::size_t (*fwrite)(const void *, std::size_t, std::size_t, FILE *) = ::fwrite;
    int (*fputc)(int, FILE *) = ::fputc;
    int (*fflush)(FILE *) = ::fflush;
    int (*fclose)(FILE *) = ::fclose;
    int (*remove)(const char *) = ::remove;
    int (*rename)(const char *, const char *) = ::rename;
};

/** Caminho de escrita de um dump f32le. Devolve os floats publicados ou 0. */
inline std::size_t write_f32(const FileOps & ops, const std::string & dir,
                             const std::string & name, const float * data,
                             std::size_t count) {
    const std::string path = dir + "/" + name + ".bin";
    const std::string tmp = path + ".tmp";
    FILE * f = ops.fopen(tmp.c_str(), "wb");
    if (f == nullptr) {
        return 0;
    }
    const std::string header =
        "# sig_dump " + name + " count=" + std::to_string(count) + " type=f32le\n";
    const std::size_t hdr = ops.fwrite(header.data(), 1, header.size(), f);
    const std::size_t written = ops.fwrite(data, sizeof(float), count, f);
    const int flush_rc = ops.fflush(f);
    const int close_rc = ops.fclose(f);
    if (hdr != header.size() || written != count || flush_rc != 0 || close_rc != 0) {
        ops.remove(tmp.c_str());
        return 0;
    }
    if (ops.rename(tmp.c_str(), path.c_str()) != 0) {
        ops.remove(tmp.c_str());
        return 0;
    }
    return written;
}

/** Manifesto JSONL com escrita verificada. */
class ManifestSink {
public:
    // Guarda o conjunto POR VALOR: um temporário (ops()) não pode virar
    // referência pendente.
    ManifestSink(FileOps ops, std::string path)
        : ops_(ops), path_(std::move(path)) {}

    bool open() {
        f_ = ops_.fopen(path_.c_str(), "wb");
        ok_ = (f_ != nullptr);
        return ok_;
    }
    bool write(const std::string & line) {
        if (f_ == nullptr) {
            return false;
        }
        const std::size_t n = ops_.fwrite(line.data(), 1, line.size(), f_);
        const int nl = ops_.fputc('\n', f_);
        const int fl = ops_.fflush(f_);
        if (n != line.size() || nl == EOF || fl != 0) {
            ok_ = false;
            return false;
        }
        return true;
    }
    bool close() {
        if (f_ != nullptr) {
            if (ops_.fflush(f_) != 0) ok_ = false;
            if (ops_.fclose(f_) != 0) ok_ = false;
            f_ = nullptr;
        }
        return ok_;
    }
    bool saudavel() const { return ok_; }

private:
    FileOps ops_;
    std::string path_;
    FILE * f_ = nullptr;
    bool ok_ = false;
};

} // namespace sig_dump_io

#endif
