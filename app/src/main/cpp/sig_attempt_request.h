// R25/REQ — PEDIDO DA TENTATIVA (fotografia imutável) transportado NA CHAMADA.
//
// Defeito que isto corrige (R24): a transcrição não recebia a tentativa. O que
// existia era (a) uma tabela lateral publicada antes (`sig_attempt_opts.h`) e
// consumida com `take(owner, 0)` — wildcard de attempt, sem identidade — e
// (b) knobs de política em `setenv` do processo, que qualquer job seguinte
// herdava e que ninguém limpava no DEFAULT.
//
// Contrato novo:
//   * o pedido viaja como TEXTO na própria chamada de transcrição (entrada JNI
//     aditiva `transcribeOwnedReq`), então não há janela entre publicar e usar;
//   * é validado e COPIADO para uma struct local antes de qualquer compute;
//   * `DEFAULT` é uma decisão explícita: o domínio é limpo, não herdado;
//   * política não implementada devolve UNSUPPORTED (nunca "aplicado");
//   * não usa thread-local: quem transcreve recebe o pedido por parâmetro.
//
// Formato (uma linha, pares `chave=valor` separados por `;`):
//
//   v=1;token=<id>;owner=<n>;gen=<n>;job=<n>;attempt=<n>
//   ;ctx=default|literal;ctx_value=<posicoes>
//   ;win=default|window;win_samples=<amostras>
//   ;submit=default|<MB>;serial=default|0|1;k=default|<n>
//   ;cap=off|on;cap_kinds=<mel,enc,nodes>;cap_dir=<path>;cap_tag=<tag>
//   ;cap_first=<n>;cap_step=<n>;cap_max=<n>;cap_steps=<n>
//   ;producer=<id>;config=<id>
//
// Regras: chave desconhecida, chave repetida, valor numérico com lixo,
// identidade ausente/inválida e combinação incoerente são INVALID (recusa
// tipada). Faixa de política fora do que o backend implementa é UNSUPPORTED.
#pragma once

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>

namespace sig_req {

enum class Status { OK, INVALID, UNSUPPORTED };

enum class CtxMode { DEFAULT, LITERAL };
enum class WinMode { DEFAULT, WINDOW };
enum class CapMode { OFF, ON };

struct Request {
    // identidade da tentativa
    std::string token;
    int64_t owner   = 0;
    int64_t gen     = 0;
    int64_t job     = 0;
    int64_t attempt = 0;

    // contexto de encoder
    CtxMode ctx       = CtxMode::DEFAULT;
    int     ctx_value = 0;
    WinMode win       = WinMode::DEFAULT;
    int64_t win_samples = 0;

    // política de submissão
    bool submit_set = false;   // false = DEFAULT (limpa o domínio)
    int  submit_mb  = 0;
    bool serial_set = false;
    int  serial     = 0;
    bool k_set      = false;
    int  k          = 0;

    // captura
    CapMode     cap       = CapMode::OFF;
    std::string cap_kinds;
    std::string cap_dir;
    std::string cap_tag;
    int         cap_first = 0;
    int         cap_step  = 0;
    int         cap_max   = 0;
    int         cap_steps = 1;
    // Filtro por grafo/call NAO existe no capturador atual: declarar aqui e
    // pedir = UNSUPPORTED (nunca "ignorado em silencio").
    std::string cap_graph;
    int         cap_call  = -1;

    // procedência
    std::string producer;
    std::string config;

    bool identidade_ok() const {
        return !token.empty() && owner > 0 && gen > 0 && job > 0 && attempt > 0;
    }
};

struct Result {
    Status status = Status::OK;
    std::string error;          // motivo legível (vazio quando OK)
    std::string key_of_error;   // chave culpada, quando houver

    bool ok() const { return status == Status::OK; }
};

namespace detail {

inline bool parse_i64(const std::string & v, int64_t * out) {
    if (v.empty()) {
        return false;
    }
    char * fim = nullptr;
    const long long x = strtoll(v.c_str(), &fim, 10);
    if (fim == v.c_str() || fim == nullptr || *fim != '\0') {
        return false;
    }
    *out = static_cast<int64_t>(x);
    return true;
}

inline bool parse_i32(const std::string & v, int * out) {
    int64_t x = 0;
    if (!parse_i64(v, &x)) {
        return false;
    }
    if (x < -2147483647LL || x > 2147483647LL) {
        return false;
    }
    *out = static_cast<int>(x);
    return true;
}

inline bool bool_word(const std::string & v, bool * out) {
    if (v == "1" || v == "true") { *out = true;  return true; }
    if (v == "0" || v == "false") { *out = false; return true; }
    return false;
}

} // namespace detail

// Valida o pedido ja preenchido. Separado do parse para o teste poder exercitar
// as regras de coerencia sem montar texto.
inline Result validate(const Request & r) {
    Result res;
    if (!r.identidade_ok()) {
        res.status = Status::INVALID;
        res.error = "identidade incompleta (token/owner/gen/job/attempt)";
        res.key_of_error = r.token.empty() ? "token" : "identidade";
        return res;
    }
    if (r.ctx == CtxMode::LITERAL) {
        if (r.ctx_value <= 0 || r.ctx_value > 1500) {
            res.status = Status::UNSUPPORTED;
            res.error = "ctx literal fora de [1,1500]";
            res.key_of_error = "ctx_value";
            return res;
        }
    }
    if (r.win == WinMode::WINDOW && r.win_samples <= 0) {
        res.status = Status::INVALID;
        res.error = "win=window sem amostras";
        res.key_of_error = "win_samples";
        return res;
    }
    if (r.submit_set && (r.submit_mb < 0 || r.submit_mb > 4096)) {
        res.status = Status::UNSUPPORTED;
        res.error = "submit fora de [0,4096] MB";
        res.key_of_error = "submit";
        return res;
    }
    if (r.serial_set && r.serial != 0 && r.serial != 1) {
        res.status = Status::INVALID;
        res.error = "serial fora de {0,1}";
        res.key_of_error = "serial";
        return res;
    }
    if (r.k_set && (r.k < 0 || r.k > 64)) {
        res.status = Status::UNSUPPORTED;
        res.error = "k fora de [0,64] (admissao nao implementada acima disso)";
        res.key_of_error = "k";
        return res;
    }
    if (r.cap == CapMode::ON) {
        if (r.cap_kinds.empty()) {
            res.status = Status::INVALID;
            res.error = "cap=on sem cap_kinds";
            res.key_of_error = "cap_kinds";
            return res;
        }
        if (r.cap_dir.empty() || r.cap_tag.empty()) {
            res.status = Status::INVALID;
            res.error = "cap=on sem cap_dir/cap_tag";
            res.key_of_error = r.cap_dir.empty() ? "cap_dir" : "cap_tag";
            return res;
        }
        if (r.cap_first < 0 || r.cap_step < 0 || r.cap_max < 0 || r.cap_steps < 1) {
            res.status = Status::INVALID;
            res.error = "selecao de captura invalida";
            res.key_of_error = "cap_first/cap_step/cap_max/cap_steps";
            return res;
        }
        if (!r.cap_graph.empty() || r.cap_call >= 0) {
            res.status = Status::UNSUPPORTED;
            res.error = "filtro cap_graph/cap_call nao implementado no capturador";
            res.key_of_error = !r.cap_graph.empty() ? "cap_graph" : "cap_call";
            return res;
        }
    }
    return res;
}

// Parse + validate. `text` vazio/null é INVALID: ausência de pedido NÃO é
// DEFAULT silencioso (quem quer DEFAULT manda ctx=default;submit=default;...).
inline Result parse(const char * text, Request * out) {
    Result res;
    if (out == nullptr) {
        res.status = Status::INVALID;
        res.error = "destino nulo";
        return res;
    }
    if (text == nullptr || text[0] == '\0') {
        res.status = Status::INVALID;
        res.error = "pedido vazio";
        res.key_of_error = "v";
        return res;
    }

    Request r;
    int versao = 0;
    bool tem_versao = false;
    std::string vistas;   // R25/REQ — toda chave e unica (semantica declarada uma vez)

    std::string texto(text);
    std::size_t pos = 0;
    while (pos <= texto.size()) {
        const std::size_t fim = texto.find(';', pos);
        const std::string par = texto.substr(pos, fim == std::string::npos ? std::string::npos : fim - pos);
        pos = (fim == std::string::npos) ? texto.size() + 1 : fim + 1;
        if (par.empty()) {
            continue;
        }
        const std::size_t eq = par.find('=');
        if (eq == std::string::npos || eq == 0) {
            res.status = Status::INVALID;
            res.error = "par sem chave=valor";
            return res;
        }
        const std::string k = par.substr(0, eq);
        const std::string v = par.substr(eq + 1);

        if (("|" + vistas + "|").find("|" + k + "|") != std::string::npos) {
            res.status = Status::INVALID;
            res.error = "chave repetida";
            res.key_of_error = k;
            return res;
        }
        vistas += (vistas.empty() ? "" : "|") + k;

        if (k == "v") {
            tem_versao = true;
            if (!detail::parse_i32(v, &versao) || versao != 1) {
                res.status = Status::INVALID;
                res.error = "versao nao suportada";
                res.key_of_error = k;
                return res;
            }
        } else if (k == "token") {
            r.token = v;
        } else if (k == "owner") {
            if (!detail::parse_i64(v, &r.owner)) { res.status = Status::INVALID; res.error = "owner nao numerico"; res.key_of_error = k; return res; }
        } else if (k == "gen") {
            if (!detail::parse_i64(v, &r.gen)) { res.status = Status::INVALID; res.error = "gen nao numerico"; res.key_of_error = k; return res; }
        } else if (k == "job") {
            if (!detail::parse_i64(v, &r.job)) { res.status = Status::INVALID; res.error = "job nao numerico"; res.key_of_error = k; return res; }
        } else if (k == "attempt") {
            if (!detail::parse_i64(v, &r.attempt)) { res.status = Status::INVALID; res.error = "attempt nao numerico"; res.key_of_error = k; return res; }
        } else if (k == "ctx") {
            if (v == "default") { r.ctx = CtxMode::DEFAULT; }
            else if (v == "literal") { r.ctx = CtxMode::LITERAL; }
            else { res.status = Status::INVALID; res.error = "ctx desconhecido"; res.key_of_error = k; return res; }
        } else if (k == "ctx_value") {
            if (!detail::parse_i32(v, &r.ctx_value)) { res.status = Status::INVALID; res.error = "ctx_value nao numerico"; res.key_of_error = k; return res; }
        } else if (k == "win") {
            if (v == "default") { r.win = WinMode::DEFAULT; }
            else if (v == "window") { r.win = WinMode::WINDOW; }
            else { res.status = Status::INVALID; res.error = "win desconhecido"; res.key_of_error = k; return res; }
        } else if (k == "win_samples") {
            if (!detail::parse_i64(v, &r.win_samples)) { res.status = Status::INVALID; res.error = "win_samples nao numerico"; res.key_of_error = k; return res; }
        } else if (k == "submit") {
            if (v == "default") { r.submit_set = false; }
            else {
                if (!detail::parse_i32(v, &r.submit_mb)) { res.status = Status::INVALID; res.error = "submit nao numerico"; res.key_of_error = k; return res; }
                r.submit_set = true;
            }
        } else if (k == "serial") {
            if (v == "default") { r.serial_set = false; }
            else {
                if (!detail::parse_i32(v, &r.serial)) { res.status = Status::INVALID; res.error = "serial nao numerico"; res.key_of_error = k; return res; }
                r.serial_set = true;
            }
        } else if (k == "k") {
            if (v == "default") { r.k_set = false; }
            else {
                if (!detail::parse_i32(v, &r.k)) { res.status = Status::INVALID; res.error = "k nao numerico"; res.key_of_error = k; return res; }
                r.k_set = true;
            }
        } else if (k == "cap") {
            if (v == "off") { r.cap = CapMode::OFF; }
            else if (v == "on") { r.cap = CapMode::ON; }
            else { res.status = Status::INVALID; res.error = "cap desconhecido"; res.key_of_error = k; return res; }
        } else if (k == "cap_kinds") {
            r.cap_kinds = v;
        } else if (k == "cap_dir") {
            r.cap_dir = v;
        } else if (k == "cap_tag") {
            r.cap_tag = v;
        } else if (k == "cap_first") {
            if (!detail::parse_i32(v, &r.cap_first)) { res.status = Status::INVALID; res.error = "cap_first nao numerico"; res.key_of_error = k; return res; }
        } else if (k == "cap_step") {
            if (!detail::parse_i32(v, &r.cap_step)) { res.status = Status::INVALID; res.error = "cap_step nao numerico"; res.key_of_error = k; return res; }
        } else if (k == "cap_max") {
            if (!detail::parse_i32(v, &r.cap_max)) { res.status = Status::INVALID; res.error = "cap_max nao numerico"; res.key_of_error = k; return res; }
        } else if (k == "cap_steps") {
            if (!detail::parse_i32(v, &r.cap_steps)) { res.status = Status::INVALID; res.error = "cap_steps nao numerico"; res.key_of_error = k; return res; }
        } else if (k == "cap_graph") {
            r.cap_graph = v;
        } else if (k == "cap_call") {
            if (!detail::parse_i32(v, &r.cap_call)) { res.status = Status::INVALID; res.error = "cap_call nao numerico"; res.key_of_error = k; return res; }
        } else if (k == "producer") {
            r.producer = v;
        } else if (k == "config") {
            r.config = v;
        } else {
            // Chave desconhecida e RECUSA: um pedido que o nativo nao entende
            // nao pode ser aplicado pela metade.
            res.status = Status::INVALID;
            res.error = "chave desconhecida";
            res.key_of_error = k;
            return res;
        }
    }

    if (!tem_versao) {
        res.status = Status::INVALID;
        res.error = "sem versao (v=1)";
        res.key_of_error = "v";
        return res;
    }
    res = validate(r);
    if (res.ok()) {
        *out = r;
    }
    return res;
}

} // namespace sig_req
