// ============================================================================
// PARSE DE ARGUMENTOS — harness de diagnostico Hy-MT-2 (arm64-v8a).
//
// Reescrito em 29/09/2026 a partir das especificacoes do parecer.
//
// POR QUE REESCREVER: a versao anterior lia argv[1..9] POR INDICE e so depois
// varria as opcoes nomeadas, sem remove-las do argv. Consequencia MEDIDA:
// "--prompt-file ARQ" caia em argv[8] (a lista de IDs) e virava "1 ids fixos".
// O harness rodava no modo errado, gerava "!" com 1 token e imprimia
// "VEREDITO: GEROU_TEXTO" — literalmente verdade, e por isso nao acusava o
// erro de medicao. 16 execucoes de Q5 nasceram assim.
//
// Aqui: UMA passagem, nome para tudo, e validacao que RECUSA ambiguidade em vez
// de adivinhar. Nenhum atoi silencioso.
// ============================================================================
#ifndef HARNESS_HARGS_SEEN
#define HARNESS_HARGS_SEEN

#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>
#include <fstream>
#include <sstream>

namespace hargs {

struct Args {
    std::string modelo;
    std::string backend      = "cpu";
    std::string dump_prefix;
    std::string prompt_path;          // vazio = prompt embutido no codigo
    std::string ids_de;               // procedencia dos ids, para o log
    int   n_eval        = 8;
    int   n_gpu         = 0;
    int   n_ctx         = 512;
    int   n_batch       = 128;
    int   usar_template = 0;
    bool  raw           = false;
    // greedy = argmax (diagnostico numerico); teacher = ids fixos (replay);
    // app = a cadeia REAL do app (representatividade de produto).
    enum Modo { GREEDY, TEACHER, APP } ;
    Modo  modo           = GREEDY;
    std::vector<int> fixos;

    // ---- parametros da cadeia de amostragem do APP (5.3 do prompt de 29/09) ----
    //
    // O app NAO faz argmax. Em llama_jni.cpp:442-449 a cadeia e':
    //   penalties(last_n=64, repeat=1.05, freq=0, present=0)
    //   -> top_k(20) -> top_p(0.6) -> temp(0.7) -> dist(42)
    // As penalidades sao neutras (o especialista confirmou no fork), mas
    // top_k/top_p/temp NAO sao: eles recortam a distribuicao antes da escolha.
    // O dist(42) torna o processo reproduzivel, mas a escolha depende da PDF
    // sobre o top-20 — nao do argmax. Medir argmax e medir o caminho do app sao
    // coisas diferentes, e o Q5/Q6 mediram a primeira.
    int   top_k         = 20;
    float top_p         = 0.6f;
    float temperature   = 0.7f;
    float repeat_penalty= 1.05f;
    int   penalty_last_n= 64;
    unsigned dist_seed   = 42;

    // tokenizacao: o app usa add_special=false, parse_special=true.
    // Manter isto explicito e nomeado, e nao derivado de --raw, porque sao
    // coisas diferentes: --raw diz que o ARQUIVO e' o prompt, nao se inserem
    // tokens especiais.
    bool  add_special    = false;   // padrao = o do app
    bool  parse_special  = true;    // padrao = o do app

    // ACEITACAO DUPLA de penalidades (experimento legado, NAO e' o app).
    // FATO VERIFICADO na rodada 10: llama_sampler_sample() chama
    // llama_sampler_accept() internamente (llama-sampler.cpp:874) no caminho
    // host — que e' o que app e harness usam (set_sampler nunca e' chamado).
    // Logo o historico e' alimentado UMA vez por token gerado, mesmo sem
    // nenhuma chamada explicita. Ligar esta flag ADICIONA uma segunda
    // aceitacao explicita por passo: com freq=present=0 a penalidade NAO
    // duplica (conta nao entra na escala), mas o anel de penalty_last_n
    // recebe o token 2x -> janela efetiva encurta e expulsao adianta.
    bool  aceitacao_dupla = false;

    // Modo BENCHMARK (§4.3/§5.5 do parecer 29/09): sem varredura completa de
    // finitude, sem top-5 por passo, sem linha de log por token — nada disso
    // existe no app. O log declara explicitamente "finitude NAO VERIFICADA".
    // Padrao = false = MODO DIAGNOSTICO (validacao numerica completa).
    bool  modo_benchmark = false;

    // §4.1 rodada 11: gravar dump SO do passo N (-1 = todos, como antes).
    // O replay focal precisa so de L[342]; 343 dumps de 483 KB nao agregam.
    int   dump_passo    = -1;
    // §3 rodada 11: incluir linhas DEBUG do llama (atribuicao layer->device
    // em llama-model.cpp:1323 e' DEBUG) para provar colocacao real.
    bool log_debug     = false;
    // §6.3 rodada 12: captura do OPERANDO que entra no caminho de saida —
    // hidden state (n_embd floats) junto com L[342]. Discrimina: operandos
    // iguais => divergencia nasce no caminho de saida; diferentes => antes.
    bool  dump_embed    = false;
    bool  dump_produtor = false;  // rodada 14: saida do PRODUTOR (canal nextn)
    bool  parar_apos_dump = false;  // rodada 15 §4.1: encerra o protocolo apos gravar o dump alvo
    bool  dump_matriz     = false;  // rodada 15 §4.3: matriz COMPLETA das linhas do batch + .idx
    std::string canal_nextn;   // rodada 15 §5-A: nome do tensor do canal nextn (default diag_b0_entrada)
    std::string canal_pooled;  // rodada 15 §5-A: nome do tensor do canal pooled (default diag_b0_saida)
};

static void erro(const std::string& m) {
    fprintf(stderr, "HARNESS| ERRO DE ARGUMENTO: %s\n", m.c_str());
}

/**
 * Le ids de um CSV: um por linha, ou separados por , ; espaco ou tab.
 *
 * Token nao numerico e' ERRO — nunca atoi silencioso. Foi o atoi silencioso que
 * transformou o nome de um ARQUIVO em 0 e produziu "!" com 1 token, com selo
 * de sucesso. Aqui o erro aborta antes de carregar o modelo.
 */
static bool csv_ids(const std::string& csv, const std::string& origem,
                    std::vector<int>& out, std::string& erro_msg) {
    std::string cur;
    for (size_t i = 0; i <= csv.size(); i++) {
        char c = (i < csv.size()) ? csv[i] : ',';
        if (c == ',' || c == ';' || c == ' ' || c == '\t' || c == '\n' || c == '\r') {
            if (!cur.empty()) {
                for (char d : cur) {
                    if (d < '0' || d > '9') {
                        erro_msg = "id invalido '" + cur + "' em " + origem +
                                   " (esperado apenas digitos)";
                        return false;
                    }
                }
                if (cur.size() > 7) { erro_msg = "id fora de faixa: " + cur; return false; }
                out.push_back(atoi(cur.c_str()));
                cur.clear();
            }
        } else {
            cur.push_back(c);
        }
    }
    if (out.empty() && erro_msg.empty()) erro_msg = "nenhum id em " + origem;
    return !out.empty();
}

static bool ler_ids_arquivo(const std::string& path, std::vector<int>& out,
                            std::string& erro_msg) {
    std::ifstream f(path, std::ios::binary);
    if (!f) { erro_msg = "nao abri o arquivo de ids: " + path; return false; }
    std::stringstream ss; ss << f.rdbuf();
    if (!csv_ids(ss.str(), path, out, erro_msg)) {
        if (erro_msg.empty()) erro_msg = "arquivo de ids vazio: " + path;
        return false;
    }
    return true;
}

// Consome o valor que segue uma opcao nomeada. Retorna nullptr (e' um erro)
// quando a opcao e' a ultima do comando linha — o caso em que a versao
// anterior lia lixo da pilha.
static const char* pega_valor(int argc, char** argv, int& i, const char* nome,
                              std::string& erro_msg) {
    if (i + 1 >= argc) { erro_msg = std::string(nome) + " exige um valor"; return nullptr; }
    return argv[++i];
}

static bool parse(int argc, char** argv, Args& A, std::string& erro_msg) {
    bool viu_n = false, viu_ids = false, viu_modo = false, viu_template = false;
    std::vector<std::string> pos;              // posicionais legados (deprecados)

    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        const char* (*need)(int, char**, int&, const char*, std::string&) = pega_valor;
        if      (a == "--modelo")      { const char* v = need(argc, argv, i, "--modelo", erro_msg);     if (!v) return false; A.modelo = v; }
        else if (a == "--backend")     { const char* v = need(argc, argv, i, "--backend", erro_msg);    if (!v) return false; A.backend = v; }
        else if (a == "--n-eval")      { const char* v = need(argc, argv, i, "--n-eval", erro_msg);     if (!v) return false; A.n_eval = atoi(v); viu_n = true; }
        else if (a == "--gpu-layers")  { const char* v = need(argc, argv, i, "--gpu-layers", erro_msg); if (!v) return false; A.n_gpu = atoi(v); }
        else if (a == "--ctx")         { const char* v = need(argc, argv, i, "--ctx", erro_msg);        if (!v) return false; A.n_ctx = atoi(v); }
        else if (a == "--batch")       { const char* v = need(argc, argv, i, "--batch", erro_msg);      if (!v) return false; A.n_batch = atoi(v); }
        else if (a == "--template")    { const char* v = need(argc, argv, i, "--template", erro_msg);   if (!v) return false; A.usar_template = atoi(v); viu_template = true; }
        else if (a == "--prompt-file") { const char* v = need(argc, argv, i, "--prompt-file", erro_msg); if (!v) return false; A.prompt_path = v; }
        else if (a == "--raw")         { A.raw = true; }
        else if (a == "--ids")         { const char* v = need(argc, argv, i, "--ids", erro_msg); if (!v) return false; A.fixos.clear();
                                        std::string e;
                                        if (!ler_ids_arquivo(v, A.fixos, e)) { erro_msg = e; return false; }
                                        A.ids_de = std::string("arquivo ") + v; viu_ids = true; }
        else if (a == "--ids-inline")  { const char* v = need(argc, argv, i, "--ids-inline", erro_msg); if (!v) return false; A.fixos.clear();
                                        std::string e;
                                        if (!csv_ids(v, "--ids-inline", A.fixos, e)) { erro_msg = e; return false; }
                                        A.ids_de = "--ids-inline"; viu_ids = true; }
        else if (a == "--mode")        { const char* v = need(argc, argv, i, "--mode", erro_msg); if (!v) return false;
                                        if      (strcmp(v, "greedy")  == 0) A.modo = Args::GREEDY;
                                        else if (strcmp(v, "teacher") == 0) A.modo = Args::TEACHER;
                                        else if (strcmp(v, "app")     == 0) A.modo = Args::APP;
                                        else { erro_msg = std::string("--mode invalido: ") + v +
                                                          " (greedy|teacher|app)"; return false; }
                                        viu_modo = true; }
        // ---- parametros da cadeia do app (modo app) ----
        else if (a == "--top-k")        { const char* v = need(argc, argv, i, "--top-k", erro_msg); if (!v) return false; A.top_k = atoi(v); }
        else if (a == "--top-p")        { const char* v = need(argc, argv, i, "--top-p", erro_msg); if (!v) return false; A.top_p = (float)atof(v); }
        else if (a == "--temp")         { const char* v = need(argc, argv, i, "--temp", erro_msg); if (!v) return false; A.temperature = (float)atof(v); }
        else if (a == "--repeat-penalty") { const char* v = need(argc, argv, i, "--repeat-penalty", erro_msg); if (!v) return false; A.repeat_penalty = (float)atof(v); }
        else if (a == "--dist-seed")    { const char* v = need(argc, argv, i, "--dist-seed", erro_msg); if (!v) return false; A.dist_seed = (unsigned)atoi(v); }
        else if (a == "--add-special")  { const char* v = need(argc, argv, i, "--add-special", erro_msg); if (!v) return false; A.add_special = (atoi(v) != 0); }
        else if (a == "--parse-special"){ const char* v = need(argc, argv, i, "--parse-special", erro_msg); if (!v) return false; A.parse_special = (atoi(v) != 0); }
        else if (a == "--accept-duplo") { A.aceitacao_dupla = true; }
        else if (a == "--benchmark")       { A.modo_benchmark = true; }
        else if (a == "--dump-passo") { const char* v = need(argc, argv, i, "--dump-passo", erro_msg); if (!v) return false; A.dump_passo = atoi(v); }
        else if (a == "--log-debug")  { A.log_debug = true; }
        else if (a == "--dump-embed") { A.dump_embed = true; }
        else if (a == "--dump-produtor") { A.dump_produtor = true; }
        else if (a == "--parar-apos-dump") { A.parar_apos_dump = true; }
        else if (a == "--dump-matriz")     { A.dump_matriz = true; }
        else if (a == "--canal-nextn")  { const char* v = need(argc, argv, i, "--canal-nextn", erro_msg); if (!v) return false; A.canal_nextn = v; }
        else if (a == "--canal-pooled") { const char* v = need(argc, argv, i, "--canal-pooled", erro_msg); if (!v) return false; A.canal_pooled = v; }
        else if (a == "--aceitar-penalidade") {
            // Renomeada na rodada 10: o nome antigo sugeria "habilitar uma
            // penalidade antes inativa", o que e' falso — o penalizador ja
            // esta ativo (repeat=1.05) e ja e' alimentado por sample().
            // Recusa explicita (rc=2) em vez de aceitar silenciosamente.
            erro_msg = "--aceitar-penalidade foi renomeada. sample() ALIMENTA o "
                       "historico sozinho (llama-sampler.cpp:874). Use "
                       "--accept-duplo se quiser uma SEGUNDA aceitacao "
                       "explicita por passo (nao representa o app).";
            return false;
        }
        else if (a == "--dump-prefix") { const char* v = need(argc, argv, i, "--dump-prefix", erro_msg); if (!v) return false; A.dump_prefix = v; }
        else if (a == "--help" || a == "-h") { erro_msg = "__HELP__"; return false; }
        // 3.1: opção desconhecida ABORTA. Nunca vira token, nunca vira posicional.
        else if (a.size() > 1 && a[0] == '-') { erro_msg = "opcao desconhecida: " + a; return false; }
        else pos.push_back(a);
    }

    // ---- posicionais legados: aceitos, DEPRECADOS, nunca colidem com opcoes ----
    if (!pos.empty()) {
        fprintf(stderr, "HARNESS| AVISO: argumentos POSICIONAIS legados em uso; "
                        "migre para as opcoes nomeadas (--help). Medicoes antigas "
                        "dependiam deles e sao a origem do defeito corrigido aqui.\n");
        size_t k = 0;
        if (A.modelo.empty()   && k < pos.size()) A.modelo = pos[k++];
        if (!viu_n             && k < pos.size()) A.n_eval = atoi(pos[k++].c_str());
        if (k < pos.size())     A.backend = pos[k++];
        if (k < pos.size())     A.n_gpu  = atoi(pos[k++].c_str());
        if (k < pos.size())     A.n_ctx  = atoi(pos[k++].c_str());
        if (k < pos.size())     A.n_batch = atoi(pos[k++].c_str());
        if (!viu_template      && k < pos.size()) A.usar_template = atoi(pos[k++].c_str());
        if (!viu_ids           && k < pos.size() && !pos[k].empty()) {
            std::string e;
            if (!csv_ids(pos[k], "posicional", A.fixos, e)) { erro_msg = e; return false; }
            A.ids_de = "posicional"; viu_ids = true;
        }
        if (k < pos.size())     A.dump_prefix = pos[k++];
        if (k < pos.size()) {
            erro_msg = "ha " + std::to_string(pos.size()) + " posicionais; o legado aceita 9. "
                       "Provavelmente uma opcao nomeada escrita sem '--'.";
            return false;
        }
    }

    // =================== VALIDACOES CRUZADAS ===================
    if (A.modelo.empty()) { erro_msg = "falta --modelo (ou o 1o posicional)"; return false; }
    {
        std::ifstream f(A.modelo, std::ios::binary);
        if (!f) { erro_msg = "nao abri o modelo: " + A.modelo; return false; }
    }
    if (A.backend != "cpu" && A.backend != "vulkan" && A.backend != "opencl") {
        erro_msg = "backend invalido: " + A.backend + " (cpu|vulkan|opencl)"; return false;
    }
    // 3.2: backend pedido e offload sao uma coisa so. A execucao pede CPU e
    // manda -1 GPU: o harness antigo emitia um AVISO e seguia, medindo Vulkan e
    // rotulando de CPU. Aqui a contradicao ABORTA antes de carregar o modelo.
    if (A.backend == "cpu" && A.n_gpu != 0) {
        erro_msg = "backend=cpu exige n_gpu_layers=0, mas veio " + std::to_string(A.n_gpu) +
                   ". O harness nao normaliza: a medicao seria de outro backend. "
                   "Use --gpu-layers 0, ou troque o backend.";
        return false;
    }
    if (A.backend != "cpu" && A.n_gpu == 0) {
        erro_msg = "backend=" + A.backend + " com n_gpu_layers=0 nao usaria a GPU.";
        return false;
    }
    if (A.raw && A.prompt_path.empty()) {
        erro_msg = "--raw exige --prompt-file (o arquivo passa a SER o prompt).";
        return false;
    }
    if (viu_ids && A.modo != Args::TEACHER) {
        // 3.3: lista de IDs so tem sentido em teacher forcing. Sem --mode
        // explicito, recusar e melhor que escolher por conta propria.
        erro_msg = "foi fornecida lista de IDs, que so vale em teacher forcing. "
                   "Informe --mode teacher, ou remova os ids para gerar em greedy.";
        return false;
    }
    if (!A.prompt_path.empty()) {
        std::ifstream f(A.prompt_path, std::ios::binary);
        if (!f) { erro_msg = "nao abri o prompt-file: " + A.prompt_path; return false; }
    }
    if (A.n_eval < 1)   { erro_msg = "--n-eval precisa ser >= 1"; return false; }
    if (A.n_eval > 2048) { erro_msg = "--n-eval acima do teto do harness (2048)"; return false; }
    if (A.n_ctx  < 64)     A.n_ctx  = 64;
    if (A.n_ctx  > 32768)  A.n_ctx  = 32768;
    if (A.n_batch < 8)     A.n_batch = 8;
    if (A.n_batch > 4096)  A.n_batch = 4096;
    (void)viu_n;
    return true;
}

/**
 * A configuracao efetiva, impressa ANTES de medir.
 *
 * Sem isto, o log de uma execucao nao dizia o que foi de fato executado — foi
 * como 16 medicoes de Q5 pareceram validas enquanto rodavam no modo errado.
 */
static void imprimir(const Args& A) {
    fprintf(stderr, "HARNESS| ---- CONFIGURACAO EFETIVA (o que sera medido) ----\n");
    fprintf(stderr, "HARNESS| modelo        = %s\n", A.modelo.c_str());
    const char* modo_nome = A.modo == Args::GREEDY ? "greedy"
                         : A.modo == Args::TEACHER ? "teacher forcing" : "app (cadeia real do app)";
    fprintf(stderr, "HARNESS| modo          = %s\n", modo_nome);
    fprintf(stderr, "HARNESS| n_eval        = %d %s\n", A.n_eval,
            A.modo == Args::GREEDY ? "(teto de geracao; EOG antes e' normal)"
            : A.modo == Args::APP   ? "(teto de geracao; EOG antes e' normal)"
                                   : "(avaliacoes EXATAS; menos = FALHA)");
    if (A.modo == Args::APP)
        fprintf(stderr, "HARNESS| sampler_app   = top_k=%d top_p=%.3f temp=%.3f "
                        "repeat_penalty=%.3f last_n=%d dist_seed=%u\n",
                A.top_k, A.top_p, A.temperature, A.repeat_penalty,
                A.penalty_last_n, A.dist_seed);
    fprintf(stderr, "HARNESS| backend       = %s   n_gpu_layers = %d\n", A.backend.c_str(), A.n_gpu);
    fprintf(stderr, "HARNESS| n_ctx         = %d\n", A.n_ctx);
    fprintf(stderr, "HARNESS| n_batch       = %d\n", A.n_batch);
    fprintf(stderr, "HARNESS| template      = %d\n", A.usar_template);
    fprintf(stderr, "HARNESS| tokenizacao  = add_special=%d parse_special=%d %s\n",
            (int)A.add_special, (int)A.parse_special,
            (!A.add_special && A.parse_special) ? "[= o do app]" : "[DIVERGE DO APP]");
    fprintf(stderr, "HARNESS| modo_medida  = %s\n",
            A.modo_benchmark
                ? "BENCHMARK (sem varredura finitude, sem top-5, sem log por passo)"
                : "DIAGNOSTICO (varredura completa de finitude)");

    // O estado do penalizador precisa estar no log: duas execucoes com o
    // mesmo repeat_penalty produzem numeros diferentes conforme o numero de
    // aceitacoes por passo, e isso nao aparece em nenhum outro campo.
    {
        const char* estado =
            (A.repeat_penalty == 1.0f)
                ? "[penalizador DESLIGADO: repeat=1.0 e' o unico valor que o desativa]"
                : (A.aceitacao_dupla
                    ? "[aceitacao 2x/token (sample()+explicita): janela/evicao NAO-representativas do app]"
                    : "[aceitacao 1x/token via sample() interna — == o app (llama-sampler.cpp:874)]");
        fprintf(stderr,
                "HARNESS| penalidade    = repeat=%.3f last_n=%d aceitacoes_por_token=%d %s\n",
                (double)A.repeat_penalty, A.penalty_last_n,
                A.aceitacao_dupla ? 2 : 1, estado);
    }
    fprintf(stderr, "HARNESS| prompt_file   = %s%s\n",
            A.prompt_path.empty() ? "(prompt embutido)" : A.prompt_path.c_str(),
            A.raw ? "  [RAW: o arquivo e' o prompt, sem template/instrucao]" : "");
    if (A.fixos.empty())
        fprintf(stderr, "HARNESS| ids_fixos     = (nenhum) -> %s; EOG pode encerrar antes do teto\n", modo_nome);
    else
        fprintf(stderr, "HARNESS| ids_fixos     = %zu ids de [%s]\n", A.fixos.size(), A.ids_de.c_str());
    fprintf(stderr, "HARNESS| dump_prefix   = %s\n",
            A.dump_prefix.empty() ? "(sem dump)" : A.dump_prefix.c_str());
    fprintf(stderr, "HARNESS| ---- FIM CONFIGURACAO ----\n");
}

} // namespace hargs
#endif // HARNESS_HARGS_SEEN
