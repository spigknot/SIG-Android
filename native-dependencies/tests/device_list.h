// LISTA DE DEVICES NULL-TERMINADA — rodada 11, §2 do parecer de 29/09 19:53.
//
// O contrato (llama.h, struct llama_model_params.devices):
//   "NULL-terminated list of devices to use for offloading (if NULL, all
//    available devices are used)"
// O percurso real (llama.cpp:155 e :173):
//   while (params.devices[n_devs]) n_devs++;
//   for (ggml_backend_dev_t * dev = params.devices; *dev; ++dev) ...
//
// O defeito corrigido aqui: mp.devices = &dev_var apontava para UMA variavel
// sem elemento terminador. O percurso seguia lendo memoria apos a variavel
// (comportamento indefinido: parar por acaso, ou empurrar um "device" de lixo
// de pilha). Apareceu em layer_diff.cpp:70, layer_diff_v2.cpp:68 e no proprio
// harness (llama_harness.cpp:221). Helper compartilhado para nao repetir.
//
// As funcoes de logica sao PURAS (sem ggml): o teste de construcao/validacao
// roda com ponteiros sinteticos, sem backend nenhum (§2.3).
#ifndef DEVICE_LIST_H
#define DEVICE_LIST_H

#include <cstddef>
#include <cstring>

// Tipo opaco identico ao da API (sem incluir ggml-backend.h: o teste usa
// ponteiros sinteticos; quem linka de verdade ja tem o tipo real).
typedef struct ggml_backend_device * ggml_backend_dev_t_x;

// (Para o resto do codigo, ggml_backend_dev_t REAL: quem usa a lista ja
// incluiu ggml-backend.h antes deste header; a typedef acima so serve ao
// teste puro. Usamos template para nao conflitar.)

template <typename Dev>
struct ListaDevices {
    static const int CAP = 8;                 // capacidade (1 terminador incluso)
    Dev               buf[CAP];               // buf[n] == nullptr SEMPRE
    int               n;

    // Array INTEIRO zerado (previsibilidade total: o consumidor percorre ate
    // o primeiro nullptr; cauda NAO precisa ser validada porque ja e' nula).
    ListaDevices() : n(0) {
        for (int i = 0; i < CAP; i++) buf[i] = nullptr;
    }

    // Adiciona um device; false = cheio ou nulo (nunca deixa a lista sem
    // terminador, nem silencia overflow).
    bool add(Dev d) {
        if (d == nullptr)      return false;
        if (n >= CAP - 1)      return false;  // reserva 1 slot p/ terminador
        buf[n++] = d;
        buf[n]   = nullptr;
        return true;
    }

    Dev*       data()       { return buf; }   // lifetime: proprio objeto
    int        size() const { return n; }
    bool       vazia() const{ return n == 0; }

    // CONTRATO EXATO (§3.1, rodada 12): a API do llama percorre ate o
    // PRIMEIRO nullptr — nao ha exigencia de cauda toda nula depois dele.
    // Valida as tres condicoes que importam:
    //   1. n dentro dos limites [0, CAP-1] (1 slot reservado ao terminador);
    //   2. os n elementos anteriores sao todos nao-nulos;
    //   3. terminador exatamente em buf[n].
    // A cauda buf[n+1..CAP-1] nao e' lida: o construtor ja a zera e nenhum
    // metodo escreve alem de n (add recusa overflow), entao permanece nula.
    bool valida() const {
        if (n < 0 || n >= CAP) return false;
        for (int k = 0; k < n; k++) if (buf[k] == nullptr) return false;
        return buf[n] == nullptr;
    }
};

// ---- funcoes puras de decisao (testaveis com strings/ponteiros sinteticos) ----

// Coerencia backend x offload (espelha a regra ja exigida do parse, mas aqui
// e' a ultima barreira antes de carregar o modelo).
//   cpu com n_gpu>0   -> INCOERENTE (carregaria GPU rotulada de CPU)
//   gpu com n_gpu==0  -> INCOERENTE (nada sairia da CPU)
inline bool config_coerente(const char* backend, int n_gpu) {
    if (backend == nullptr || backend[0] == '\0') return false;
    // Somente os backends previstos (os mesmos que o parse do harness aceita:
    // cpu | vulkan | opencl — harness_args.h). Qualquer outro NOME e' rejeitado
    // explicitamente: antes, "qualquer nao-vazio != cpu" passava com n_gpu>0.
    const bool eh_cpu    = (std::strcmp(backend, "cpu") == 0);
    const bool eh_vulkan = (std::strcmp(backend, "vulkan") == 0);
    const bool eh_opencl = (std::strcmp(backend, "opencl") == 0);
    if (!eh_cpu && !eh_vulkan && !eh_opencl) return false;   // nome desconhecido
    if (eh_cpu) return n_gpu == 0;                            // cpu exige offload 0
    return n_gpu > 0;                                         // vulkan/opencl exigem offload
}

// Selecao por substring em nomes de devices. Devolve o indice, ou -1 quando
// a selecao NAO esta disponivel (o chamador DEVE recusar carregar, nunca
// cair num device por acaso). `nomes[i]` nao-nulo ate `nomes[n]==nullptr`.
inline int encontrar_indice(const char* const* nomes, int n, const char* querido) {
    if (nomes == nullptr || querido == nullptr || querido[0] == '\0') return -1;
    for (int i = 0; i < n && nomes[i] != nullptr; i++) {
        if (std::strstr(nomes[i], querido) != nullptr) return i;
    }
    return -1;
}

#endif // DEVICE_LIST_H
