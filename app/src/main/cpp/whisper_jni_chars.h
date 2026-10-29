// R4/B3 — guarda RAII para strings JNI com contagem EXATA de liberação.
//
// Motivação: `assumeContextOwned` adquiria `GetStringUTFChars(modelPath)` e
// NUNCA chamava `ReleaseStringUTFChars` (vazamento por chamada); os demais
// pontos faziam release manual espalhado por todos os caminhos de saída.
//
// Esta guarda libera EXATAMENTE UMA VEZ, em QUALQUER saída do escopo:
//   - retorno normal; retorno antecipado (recusa/false); exceção C++ lançada
//     entre a aquisição e o fim do escopo.
// E distingue "não adquirido" (GetStringUTFChars devolveu nullptr — OOM/
// exceção pendente) de "string vazia": `acquired()`==false NUNCA vira caminho
// vazio silencioso; o chamador decide (recusa explícita).
//
// O template recebe um ACCESSOR com `get()`/`release(const char*)` para que o
// comportamento seja testável em host com um fake de JNI (sem JVM):
// native-dependencies/tests/whisper/jni_chars_test.cpp. O accessor real
// (JniEnvCharsAccessor, em whisper_jni.cpp) é fino de propósito.

#ifndef SIG_WHISPER_JNI_CHARS_H
#define SIG_WHISPER_JNI_CHARS_H

template <typename Accessor>
class JniCharsGuard {
public:
    explicit JniCharsGuard(const Accessor & accessor) : accessor_(accessor) {
        chars_ = accessor_.get();
    }

    ~JniCharsGuard() {
        if (chars_ != nullptr) {
            accessor_.release(chars_);
            chars_ = nullptr;   // cinto e suspensório: nunca libera duas vezes
        }
    }

    JniCharsGuard(const JniCharsGuard &) = delete;
    JniCharsGuard & operator=(const JniCharsGuard &) = delete;

    /** Aquisição bem-sucedida? false = nullptr (OOM/exception pendente). */
    bool acquired() const { return chars_ != nullptr; }

    /** Ponteiro adquirido (nullptr quando não adquirido). */
    const char * get() const { return chars_; }

    /** Texto para leitura; "" apenas para conveniência de log — nunca para
     * transformar falha de aquisição em caminho válido vazio. */
    const char * orEmpty() const { return chars_ != nullptr ? chars_ : ""; }

private:
    Accessor accessor_;
    const char * chars_ = nullptr;
};

#endif // SIG_WHISPER_JNI_CHARS_H
