// ============================================================================
// TESTES DE PARSER DO HARNESS — executaveis no HOST, sem modelo, sem aparelho.
//
// 4.5 do prompt de 29/09: "Separe testes de parsing/logica executaveis no host
// de provas que exigem backend/aparelho. Nao exija carregar o modelo inteiro
// para testar o parser."
//
// Estes testes include() o harness_args.h e exercitam hargs::parse() direto.
// Cada caso que corresponde a um defeito REAL ja medido tem o porquê anotado.
//
// Como rodar (no repo, pelo MSYS2):
//   /c/msys64/mingw64/bin/g++.exe -std=c++17 -Wall -Wextra -I<dir do harness> \
//     harness_args_test.cpp -o harness_args_test.exe
//   ./harness_args_test.exe
//
// Sem dependencia de llama.h, ggml.h ou do modelo: e' o ponto.
// ============================================================================
#include "harness_args.h"
#include "harness_loop.h"
#include "device_list.h"
#include "harness_dump.h"

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>
#include <fstream>

static int g_pass = 0, g_fail = 0;
static std::string g_cenario;

// Um arquivo temporario com o conteudo dado, para testar --ids e --prompt-file.
static std::string escreve_tmp(const char* nome, const std::string& conteudo) {
    std::string p = std::string("tst_") + nome;
    std::ofstream f(p, std::ios::binary);
    f << conteudo;
    f.close();
    return p;
}

static void check(bool cond, const char* descricao) {
    if (cond) { g_pass++; printf("  ok   %s\n", descricao); }
    else      { g_fail++; printf("  FALHA %s\n", descricao); }
}

static void cenario(const char* nome) {
    g_cenario = nome;
    printf("\n== %s\n", nome);
}

// Constrói argv a partir de uma lista, como faria a shell.
struct Argv {
    std::vector<std::string> store;
    std::vector<char*>      p;
    Argv(std::initializer_list<std::string> l) {
        store.assign(l);
        for (auto& s : store) p.push_back(const_cast<char*>(s.c_str()));
    }
    int argc() const { return (int)p.size(); }
    char** argv() { return p.data(); }
};

// Chama parse e devolve true/false + a mensagem de erro.
static bool parse_ok(Argv& a, hargs::Args& out, std::string& err) {
    err.clear();
    return hargs::parse(a.argc(), a.argv(), out, err);
}


// ============================================================================
// §4.4 — LOGICA DO LOOP com arrays SINTETICOS (nenhuma inferencia, mock
// explicito de erro). O harness chama estas mesmas funcoes de harness_loop.h.
// ============================================================================
static int l_pass = 0, l_fail = 0;
static void lcheck(bool c, const char* d) {
    if (c) { l_pass++; printf("  ok   %s\n", d); }
    else   { l_fail++; printf("  FALHA %s\n", d); }
}
static void testar_logica_loop() {
    printf("\n== logica do loop (sintetico, 4.4)\n");

    {   // todos NaN / todos Inf / nenhum candidato elegivel
        float v[4] = {NAN, NAN, INFINITY, -INFINITY};
        Varredura r = varrer(v, 4);
        lcheck(r.nan == 2 && r.inf == 2 && !r.valido(), "varrer: 2 NaN + 2 Inf detectados");
        bool tem = true;
        int id = argmax_finito(v, 4, &tem);
        lcheck(!tem && id == -1, "greedy: todos nao-finitos => NENHUM candidato (sem id 0 fantasma)");
    }
    {   // finitos muito negativos: a sentinela antiga -1e30f excluia -1e31
        float v[3] = {-1e31f, -1e38f, -5.0f};
        bool tem = false;
        int id = argmax_finito(v, 3, &tem);
        lcheck(tem && id == 2, "greedy: finito muito negativo NAO e' excluido (-5.0 vence)");
        float u[2] = {-1e31f, -1e38f};
        id = argmax_finito(u, 2, &tem);
        lcheck(tem && id == 0, "greedy: so negativos finitos => o maior (-1e31) e' elegivel");
    }
    {   // ponteiro invalido
        Varredura r = varrer(nullptr, 0);
        lcheck(r.nan == -1 && !r.valido(), "varrer(nullptr) => invalido, nao 'zero NaN'");
    }
    {   // peca maior que buffer
        lcheck(peca_usavel(5),     "peca: retorno 5 > 0 usavel");
        lcheck(!peca_usavel(-12),  "peca: retorno negativo (tamanho) nao e' texto");
        lcheck(!peca_usavel(0),    "peca: 0 bytes e' invalida");
    }
    {   // EOG x teto: motivos separados, nunca fundidos
        lcheck(std::string(motivo_parada(false, false, true,  false)) == "EOG (fim de geracao gerado pelo modelo)", "parada: EOG nomeado");
        lcheck(std::string(motivo_parada(false, false, false, true))  == "teto de geracao atingido", "parada: teto nomeado");
        lcheck(std::string(motivo_parada(true,  true,  false, false)) == "protocolo teacher forcing completo", "parada: teacher completo");
        lcheck(std::string(motivo_parada(true,  false, false, false)) == "teacher forcing INCOMPLETO (falha)", "parada: teacher incompleto = FALHA");
    }
    {   // contagens: escolhas x decodes x EOG (4.2)
        lcheck(escolhas_consistentes(10, 9,  true,  false), "contagem: fim por EOG => decodes = escolhas-1");
        lcheck(escolhas_consistentes(10, 10, false, false), "contagem: fim por teto => decodes = escolhas");
        lcheck(escolhas_consistentes(10, 5,  false, true),  "contagem: decode falhou => decodes < escolhas");
        lcheck(!escolhas_consistentes(10, 10, true, false), "contagem: EOG com decodes=escolhas e' INCONSISTENTE");
    }
    printf("  -- logica: %d passaram, %d falharam\n", l_pass, l_fail);
    g_pass += l_pass; g_fail += l_fail;
}


// ============================================================================
// §2.3 — LISTA DE DEVICES NULL-TERMINADA (teste permanente). O defeito era
// &de_var sem terminador: o percurso do llama (llama.cpp:155/173) seguia lendo
// memoria apos a variavel. Ponteiros SINTETICOS: o teste de construcao nao
// precisa de backend nenhum.
// ============================================================================
static int d_pass = 0, d_fail = 0;
static void dcheck(bool c, const char* dsc) {
    if (c) { d_pass++; printf("  ok   %s\n", dsc); }
    else   { d_fail++; printf("  FALHA %s\n", dsc); }
}
static void testar_device_list() {
    printf("\n== device_list (sintetico, 2.3)\n");
    // o percurso identico ao do llama.cpp: caminha ate achar nullptr
    auto percurso = [](ggml_backend_dev_t_x* lista, int cap, int* n) -> bool {
        *n = 0;
        while (*n < cap) {
            if (lista[*n] == nullptr) return true;   // terminador encontrado
            (*n)++;
        }
        return false;                                // CAPOU sem terminador = defeito
    };
    {
        ListaDevices<ggml_backend_dev_t_x> L;
        dcheck(L.valida(), "lista nova vazia e valida (terminador em 0)");
        dcheck(L.vazia(), "lista nova e' vazia");
        int n = 0;
        dcheck(percurso(L.data(), ListaDevices<ggml_backend_dev_t_x>::CAP, &n) && n == 0,
               "percurso do llama para imediatamente (nullptr)");
    }
    {
        // 3 devices sinteticos (endereco qualquer nao-nulo)
        ListaDevices<ggml_backend_dev_t_x> L;
        void* p1 = (void*)0x1000; void* p2 = (void*)0x2000; void* p3 = (void*)0x3000;
        dcheck(L.add((ggml_backend_dev_t_x)p1) && L.add((ggml_backend_dev_t_x)p2) && L.add((ggml_backend_dev_t_x)p3),
               "3 devices adicionados");
        dcheck(L.valida(), "lista com 3 itens continua valida");
        int n = 0;
        dcheck(percurso(L.data(), 8, &n) && n == 3, "percurso conta EXATAMENTE 3 e para no terminador");
        dcheck(L.data()[3] == nullptr, "buf[3] == nullptr (terminador na posicao certa)");
        dcheck(L.size() == 3, "size()==3");
    }
    {
        // add(nullptr) recusa; nunca quebra o terminador
        ListaDevices<ggml_backend_dev_t_x> L;
        dcheck(!L.add(nullptr), "add(nullptr) e' recusado");
        dcheck(L.valida() && L.vazia(), "recusa nao corrompe a lista");
    }
    {
        // overflow: CAP-1 itens cabem; o ultimo slot e' DO TERMINADOR
        ListaDevices<ggml_backend_dev_t_x> L;
        bool todos = true;
        for (int i = 1; i <= 7; i++) if (!L.add((ggml_backend_dev_t_x)(long)(0x1000 + i))) todos = false;
        dcheck(todos && L.size() == 7, "7 itens cabem (CAP-1)");
        dcheck(!L.add((ggml_backend_dev_t_x)0x9999), "8o item e' RECUSADO (overflow, slot reservado p/ terminador)");
        dcheck(L.valida(), "lista ainda valida apos overflow recusado");
        int n = 0;
        dcheck(percurso(L.data(), 8, &n) && n == 7, "percurso apos overflow: 7 e para (sem lixo)");
    }
    {   // selecao indisponivel: o chamador DEVE recusar (nao cair num device por acaso)
        const char* nomes[] = {"QUALCOMM Adreno(TM) 840", "Vulkan0", nullptr};
        dcheck(encontrar_indice(nomes, 2, "Vulkan") == 1, "selecao: 'Vulkan' acha indice 1");
        dcheck(encontrar_indice(nomes, 2, "OpenCL") == -1, "selecao: 'OpenCL' INDISPONIVEL => -1 (nunca device por acaso)");
        dcheck(encontrar_indice(nomes, 2, "") == -1, "selecao: string vazia => -1");
        dcheck(encontrar_indice(nullptr, 0, "Vulkan") == -1, "selecao: lista nula => -1");
    }
    {   // coerencia backend x offload (ultima barreira antes da carga)
        dcheck(config_coerente("cpu", 0),  "coerente: cpu + n_gpu=0");
        dcheck(!config_coerente("cpu", 33), "INCOERENTE: cpu + n_gpu=33 (recusar)");
        dcheck(config_coerente("vulkan", 33), "coerente: vulkan + n_gpu=33");
        dcheck(!config_coerente("vulkan", 0), "INCOERENTE: vulkan + n_gpu=0 (recusar)");
        dcheck(!config_coerente("", 0),    "backend vazio: incoerente");
        dcheck(!config_coerente(nullptr, 0), "backend nulo: incoerente");
        // §3.2 (rodada 12): nome desconhecido e' rejeitado explicitamente —
        // antes qualquer "nao-vazio != cpu" passava com n_gpu>0.
        dcheck(!config_coerente("backend_inexistente", 1), "backend_inexistente + 1: RECUSADO");
        dcheck(!config_coerente("cuda", 8), "backend_inexistente 'cuda' + 8: RECUSADO");
        dcheck(config_coerente("opencl", 4), "coerente: opencl + n_gpu=4");
        dcheck(!config_coerente("opencl", 0), "INCOERENTE: opencl + n_gpu=0 (recusar)");
        // §3.1: valida() — as 3 condicoes do contrato
        {
            ListaDevices<ggml_backend_dev_t_x> L;
            L.add((ggml_backend_dev_t_x)0x1000);
            // forcar n invalido (simulacao de corrupcao) via const cast e' feio;
            // em vez disso testamos o que a API publica permite:
            dcheck(L.valida() && L.size()==1 && L.buf[1]==nullptr,
                   "valida: n=1, terminador em buf[1], cauda zerada pelo ctor");
            // n negativo (so acessivel corrompendo): usa um objeto copiado
            ListaDevices<ggml_backend_dev_t_x> M;
            *const_cast<int*>(&M.n) = -1;
            dcheck(!M.valida(), "valida: n=-1 (corrompido) RECUSADO");
            ListaDevices<ggml_backend_dev_t_x> N;
            *const_cast<int*>(&N.n) = 8;   // == CAP: sem slot p/ terminador
            dcheck(!N.valida(), "valida: n=CAP (sem slot de terminador) RECUSADO");
        }
    }
    printf("  -- device_list: %d passaram, %d falharam\n", d_pass, d_fail);
    g_pass += d_pass; g_fail += d_fail;
}


// ============================================================================
// §4.5 — DUMP com retorno PROPAGAVEL (teste SINTETICO, sem modelo): o caminho
// de escrita invalido tem de fazer o wrapper falhar (bool), nao so logar.
// ============================================================================
static void testar_dump_wrapper() {
    printf("\n== dump wrapper (sintetico, 4.5)\n");
    char err[512];
    float v[4] = {1.0f, 2.0f, 3.0f, 4.0f};
    {
        // diretorio inexistente => fopen falha => false (o erro NAO e' so log)
        const bool ok = gravar_dump_log("dir_inexistente_xyz/d", "P342", v, 4, err, sizeof(err));
        dcheck(!ok, "dump: diretorio inexistente => FALHA (false)");
        dcheck(err[0] != '\0', "dump: mensagem de erro preenchida");
    }
    {
        const bool ok = gravar_dump_log("", "P342", v, 4, err, sizeof(err));
        dcheck(ok, "dump: prefixo vazio (sem pedido) => true");
    }
    {
        const bool ok = gravar_dump_log("tst_ok", "P342", nullptr, 4, err, sizeof(err));
        dcheck(!ok, "dump: logits nulos => FALHA");
        const bool ok2 = gravar_dump_log("tst_ok", "P342", v, 0, err, sizeof(err));
        dcheck(!ok2, "dump: n_vocab=0 => FALHA");
    }
    {
        // caminho truncado: prefixo de ~1100 chars nao cabe no buffer de 1024
        std::string longo(1100, 'x');
        const bool ok = gravar_dump_log(longo.c_str(), "P342", v, 4, err, sizeof(err));
        dcheck(!ok, "dump: caminho truncado => FALHA");
    }
    {
        // sucesso: arquivo com tamanho EXATO (4 floats = 16 bytes)
        const bool ok = gravar_dump_log("tst_ok", "P342", v, 4, err, sizeof(err));
        dcheck(ok, "dump: escrita valida => true");
        FILE* f = fopen("tst_ok_P342.f32", "rb");
        long tam = -1;
        if (f) { fseek(f, 0, SEEK_END); tam = ftell(f); fclose(f); }
        dcheck(tam == 16, "dump: tamanho conferido = 16 bytes (4 floats)");
        remove("tst_ok_P342.f32");
    }
    printf("  -- dump: testes sinteticos executados\n");
}

int main() {
    testar_dump_wrapper();
    testar_device_list();
    testar_logica_loop();
    setvbuf(stdout, nullptr, _IONBF, 0);   // 29/09: sem isso, um crash apaga a saida

    // O parse valida a existencia do modelo: sem este arquivo, os testes
    // abaixo falhariam num aparelho "limpo" (dependencia oculta de ambiente,
    // detectada na rodada 11 no PJA110). O teste e' autossuficiente.
    { std::ofstream m("m.gguf"); m << "x"; }

    // ---- 29/09: o app NAO alimenta o historico de penalidades. Verificar que
    // o PADRAO do harness e' historico vazio, e que a opcao que liga o
    // comportamento antigo e' explicita. Sem este teste, uma refatoracao pode
    // reintroduzir llama_sampler_accept no modo app sem nenhum aviso.
    {
        hargs::Args A;
        std::string err;
        char* argv[] = {(char*)"h", (char*)"--modelo", (char*)"m.gguf",
                        (char*)"--mode", (char*)"app", nullptr};
        check(hargs::parse(5, argv, A, err), "modo app sem --accept-duplo e' aceito");
        check(!A.aceitacao_dupla, "PADRAO: historico de penalidades VAZIO (= o do app)");
        check(A.repeat_penalty != 1.0f, "repeat do app e' != 1.0, logo o penalizador fica ativo");
    }
    {
        hargs::Args A;
        std::string err;
        char* argv[] = {(char*)"h", (char*)"--modelo", (char*)"m.gguf",
                        (char*)"--mode", (char*)"app",
                        (char*)"--accept-duplo", nullptr};
        check(hargs::parse(6, argv, A, err), "--accept-duplo e' aceito");
        check(A.aceitacao_dupla, "a opcao LIGA o historico (comportamento nao-app)");
    }
    {
        // §2.4: a opcao antiga tem de ser RECUSADA com mensagem clara —
        // aceitar silenciosamente manteria um nome que sugere "habilitar
        // penalidade antes inativa", que e' falso.
        hargs::Args A;
        std::string err;
        char* argv[] = {(char*)"h", (char*)"--modelo", (char*)"m.gguf",
                        (char*)"--mode", (char*)"app",
                        (char*)"--aceitar-penalidade", nullptr};
        check(!hargs::parse(6, argv, A, err), "--aceitar-penalidade (nome antigo) e' RECUSADO rc!=0");
        check(err.find("renomeada") != std::string::npos, "a recusa explica a renomeacao");
        check(!A.aceitacao_dupla, "a recusa nao seta a flag");
    }
    {
        // --repeat-penalty 1.0 e' o unico valor que DESLIGA o penalizador
        // (llama-sampler.cpp is_disabled). O harness deve aceitar sem reclamar,
        // mas a distincao so e' observavel no log, nao no parse.
        hargs::Args A;
        std::string err;
        char* argv[] = {(char*)"h", (char*)"--modelo", (char*)"m.gguf",
                        (char*)"--mode", (char*)"app",
                        (char*)"--repeat-penalty", (char*)"1.0", nullptr};
        check(hargs::parse(7, argv, A, err), "repeat=1.0 (desliga o penalizador) e' aceito");
        check(A.repeat_penalty == 1.0f, "repeat=1.0 foi preservado");
    }

    // --------------------------------------------------------------------
    cenario("4.2(a) --prompt-file NUNCA vira lista de IDs (o defeito do argv[8])");
    {
        // Reproduz exatamente a condicao medida: a opcao nomeada ocupa a
        // posicao onde a versao antiga lia "fixos_str". Na versao antiga isto
        // produzia "modo P/D1/D2: 1 ids fixos" e gerava "!" com 1 token.
        hargs::Args A; std::string e;
        // o modelo tem de EXISTIR: senao a validacao do arquivo aborta antes
        // da de --mode, e o teste mediria a valicao errada.
        std::ofstream("m.gguf").close();
        std::ofstream("p.txt") << "ola" << std::endl;
        Argv a = {"h", "--modelo", "m.gguf", "--n-eval", "8",
                  "--prompt-file", "p.txt", "--raw", "--ids-inline", "1,2,3"};
        bool ok = parse_ok(a, A, e);
        // O parse DEVE recusar: ids + raw sem --mode e' contradicao.
        check(!ok, "prompt-file + ids sem --mode => RECUSA (nao gera '!')");
        check(A.fixos.size() == 3, "os 3 ids do --ids-inline foram lidos como ids");
        check(A.prompt_path == "p.txt", "o prompt-file ficou em prompt_path, nao em ids");
        if (e.find("--mode") == std::string::npos) printf("       [ERRO REAL: %s]\n", e.c_str());
        check(e.find("--mode") != std::string::npos, "a mensagem aponta a falta de --mode");
    }
    {
        // O mesmo prompt-file, agora SEM ids: tem de passar e nao virar id.
        hargs::Args A; std::string e;
        // modelo tem de existir: criamos um vazio
        std::ofstream("m2.gguf").close();
        std::ofstream("p2.txt") << "ola" << std::endl;
        Argv a = {"h", "--modelo", "m2.gguf", "--n-eval", "8",
                  "--prompt-file", "p2.txt", "--raw"};
        bool ok = parse_ok(a, A, e);
        check(ok, "prompt-file + raw sem ids => ACEITO");
        check(A.fixos.empty(), "nenhum id foi inventado a partir do nome do arquivo");
        check(A.raw, "raw foi registrado");
    }
    {
        // Ordem importa? "antes e depois de outras opcoes" (4.5)
        hargs::Args A1, A2; std::string e1, e2;
        std::ofstream("m3.gguf").close();
        std::ofstream("p3.txt") << "x" << std::endl;
        Argv a1 = {"h", "--prompt-file", "p3.txt", "--modelo", "m3.gguf", "--n-eval", "4", "--raw"};
        Argv a2 = {"h", "--modelo", "m3.gguf", "--n-eval", "4", "--raw", "--prompt-file", "p3.txt"};
        bool o1 = parse_ok(a1, A1, e1);
        bool o2 = parse_ok(a2, A2, e2);
        check(o1 && o2, "--prompt-file antes e depois de outras opcoes => os dois aceitos");
        check(A1.prompt_path == A2.prompt_path && A1.prompt_path == "p3.txt",
              "a posicao da opcao nao muda o resultado");
        check(A1.n_eval == 4 && A2.n_eval == 4, "n_eval identico nas duas ordens");
    }

    // --------------------------------------------------------------------
    cenario("4.2 modos explicitos: greedy NAO entra em teacher forcing");
    {
        hargs::Args A; std::string e;
        std::ofstream("m4.gguf").close();
        Argv a = {"h", "--modelo", "m4.gguf", "--n-eval", "4", "--ids-inline", "5,6"};
        bool ok = parse_ok(a, A, e);
        check(!ok, "ids sem --mode => RECUSA");
        check(A.modo == hargs::Args::GREEDY, "o modo padrao continua greedy");
    }
    {
        hargs::Args A; std::string e;
        std::ofstream("m5.gguf").close();
        Argv a = {"h", "--modelo", "m5.gguf", "--ids-inline", "5,6", "--mode", "teacher"};
        bool ok = parse_ok(a, A, e);
        check(ok, "ids + --mode teacher => ACEITO");
        check(A.modo == hargs::Args::TEACHER, "o modo ficou teacher forcing");
        check(A.fixos.size() == 2, "2 ids");
    }
    {
        hargs::Args A; std::string e;
        std::ofstream("m6.gguf").close();
        Argv a = {"h", "--modelo", "m6.gguf", "--n-eval", "2", "--mode", "guloso"};
        bool ok = parse_ok(a, A, e);
        check(!ok, "--mode guloso (typo) => RECUSA, nao vira greedy");
        check(e.find("--mode invalido") != std::string::npos, "a mensagem nomeia o modo invalido");
    }

    // --------------------------------------------------------------------
    cenario("4.2 lista vazia, ID invalido, texto no lugar de numero");
    {
        hargs::Args A; std::string e;
        std::ofstream("m7.gguf").close();
        Argv a = {"h", "--modelo", "m7.gguf", "--ids-inline", "", "--mode", "teacher"};
        check(!parse_ok(a, A, e), "lista vazia => RECUSA");
    }
    {
        hargs::Args A; std::string e;
        std::ofstream("m8.gguf").close();
        Argv a = {"h", "--modelo", "m8.gguf", "--ids-inline", "12,abc", "--mode", "teacher"};
        check(!parse_ok(a, A, e), "id invalido em --ids-inline => RECUSA");
        check(e.find("id invalido") != std::string::npos, "a mensagem diz qual id");
    }
    {
        hargs::Args A; std::string e;
        std::ofstream("m9.gguf").close();
        std::string arq = escreve_tmp("ids.txt", "46,2292\n287\n");
        Argv a = {"h", "--modelo", "m9.gguf", "--ids", arq, "--mode", "teacher"};
        check(parse_ok(a, A, e), "arquivo de ids com virgulas e quebras de linha => ACEITO");
        check(A.fixos.size() == 3, "3 ids lidos (46, 2292, 287)");
        std::remove(arq.c_str());
    }
    {
        hargs::Args A; std::string e;
        std::ofstream("m10.gguf").close();
        Argv a = {"h", "--modelo", "m10.gguf", "--ids-inline", "1.5", "--mode", "teacher"};
        check(!parse_ok(a, A, e), "numero nao inteiro (1.5) => RECUSA");
    }

    // --------------------------------------------------------------------
    cenario("4.2 valor ausente / opcao desconhecida");
    {
        hargs::Args A; std::string e;
        Argv a = {"h", "--modelo", "m11.gguf", "--n-eval"};
        check(!parse_ok(a, A, e), "--n-eval sem valor => RECUSA");
    }
    {
        hargs::Args A; std::string e;
        std::ofstream("m12.gguf").close();
        Argv a = {"h", "--modelo", "m12.gguf", "--frobnicate", "1"};
        check(!parse_ok(a, A, e), "opcao desconhecida => RECUSA");
        check(e.find("opcao desconhecida") != std::string::npos, "a mensagem nomeia a opcao");
    }

    // --------------------------------------------------------------------
    cenario("4.3 backend sem ambiguidade");
    {
        hargs::Args A; std::string e;
        std::ofstream("m13.gguf").close();
        Argv a = {"h", "--modelo", "m13.gguf", "--backend", "cpu", "--gpu-layers", "-1"};
        check(!parse_ok(a, A, e), "backend=cpu com gpu-layers=-1 => RECUSA (nao so AVISO)");
    }
    {
        hargs::Args A; std::string e;
        std::ofstream("m14.gguf").close();
        Argv a = {"h", "--modelo", "m14.gguf", "--backend", "vulkan", "--gpu-layers", "0"};
        check(!parse_ok(a, A, e), "backend=vulkan com 0 camadas => RECUSA");
    }
    {
        hargs::Args A; std::string e;
        std::ofstream("m15.gguf").close();
        Argv a = {"h", "--modelo", "m15.gguf", "--backend", "cuda"};
        check(!parse_ok(a, A, e), "backend inexistente => RECUSA");
    }
    {
        hargs::Args A; std::string e;
        std::ofstream("m16.gguf").close();
        Argv a = {"h", "--modelo", "m16.gguf", "--backend", "cpu", "--gpu-layers", "0"};
        check(parse_ok(a, A, e), "backend=cpu com 0 camadas => ACEITO (a combinacao coerente)");
    }

    // --------------------------------------------------------------------
    cenario("4.5 caminho de arquivo com espacos");
    {
        hargs::Args A; std::string e;
        std::ofstream("m 17.gguf").close();
        std::ofstream("p com espaco.txt") << "ola" << std::endl;
        Argv a = {"h", "--modelo", "m 17.gguf", "--prompt-file", "p com espaco.txt", "--raw"};
        check(parse_ok(a, A, e), "modelo e prompt com espacos => ACEITO como argumento unico");
        check(A.prompt_path == "p com espaco.txt", "o caminho com espacos foi preservado");
    }

    // --------------------------------------------------------------------
    cenario("4.2 capacidade de contexto, sem truncamento silencioso");
    {
        // O harness recusa prompt+n_eval > n_ctx? hoje isso e' checado no main.
        // Aqui verificamos apenas que n_ctx e n_batch tem piso/teto coerentes.
        hargs::Args A; std::string e;
        std::ofstream("m18.gguf").close();
        Argv a = {"h", "--modelo", "m18.gguf", "--ctx", "8", "--batch", "1", "--n-eval", "1"};
        bool ok = parse_ok(a, A, e);
        check(ok, "ctx/batch fora da faixa ainda e' normalizado, nao recusado");
        check(A.n_ctx >= 64, "n_ctx tem piso de 64 (evita contexto inutilizavel)");
        check(A.n_batch >= 8, "n_batch tem piso de 8");
    }

    // --------------------------------------------------------------------
    cenario("posicional legado ainda aceito, com aviso (nao silencioso)");
    {
        hargs::Args A; std::string e;
        std::ofstream("m19.gguf").close();
        Argv a = {"h", "m19.gguf", "4", "cpu", "0", "512", "128"};
        check(parse_ok(a, A, e), "posicionais legados ainda sao aceitos");
        check(A.modelo == "m19.gguf" && A.n_eval == 4 && A.backend == "cpu",
              "os posicionais preencheram os mesmos campos");
    }

    // --------------------------------------------------------------------
    printf("\n=====================================\n");
    printf("  %d passaram, %d falharam\n", g_pass, g_fail);
    printf("=====================================\n");
    return g_fail == 0 ? 0 : 1;
}
