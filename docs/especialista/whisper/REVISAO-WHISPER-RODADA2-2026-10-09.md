# Revisão do especialista — Whisper, rodada 2

Data: 09/10/2026. **Aceite parcial; R2-C4 ainda não está pronta para integração/promoção.** A CPU com FA é o melhor perfil provisório medido. Não foi aprovada uma política permanente de CPU obrigatório: estabilizar e acelerar Vulkan/OpenCL continua sendo objetivo.

Esta revisão leu o relatório, os fontes e evidências selecionadas; não repetiu a matriz no telefone nem os gates. Próxima ordem: PROMPT-WHISPER-RODADA3.md, nesta pasta.

## Origem e resultados aproveitáveis

- Relatório: D:\Projetos\SIG\docs\especialista\whisper\RELATORIO-WHISPER-RODADA2.txt; anexo Texto colado.txt recebido nesta conversa.
- Checkout: D:\SIG-whisper-r1, branch codex/whisper-r1, sem commit de fechamento. Evidências: D:\SIG-whisper-r1\build\whisper-rodada2\20261009-0149.
- Lib normal R2-C4: SHA-256 8ba09192d174177d6cbd1c678a40404b1b5b4ee7bbe01b5c862f592f86deb8da, conforme manifesto.
- Conferência nesta revisão: 80 fontes novos/modificados listados no manifesto têm hashes iguais aos arquivos atuais. Relatório, matriz, resumo e manifesto existem. O log final do gate unitário termina em BUILD SUCCESSFUL.
- O resolvedor core passou a selecionar OpenCL corretamente. Controles recolhidos como bytes permitiram retratar W14, antes confundido com divergência de build.
- A recuperação Vulkan ganhou preflight e recusa FA; o vazamento de vagas de compilação foi corrigido com RAII. São ganhos concretos, com alcance limitado às condições provadas.
- Parser WAV, strings JNI, resultado por sessão e cleanup coordenado receberam melhorias. O pacote br.gov.sp.pcsp.whisperlab permitiu provas ART sem substituir o SIG instalado.
- O relatório informa 630 testes unitários/0 falhas/4 skips e gates finais com exit 0. A dependência FFmpeg foi ajustada no ambiente, sem excluir teste.
- CPU FA em C01/call2: mediana 5.057,8 → 4.147,3 ms, speedup ≈1,22× e redução de tempo ≈18%. Isso ainda não prova o ganho em primeira chamada, C02, sequência multi-arquivo ou interface real.

## R3-01 — Hipótese prioritária para F-01: RNG

Em whisper.cpp/src/whisper.cpp, whisper_init_state() inicializa decoders[0].rng com std::mt19937(0), aproximadamente na linha 3523. Em whisper_full_with_state(), o laço de reseed começa em j=1, aproximadamente na linha 6944. O decoder principal não é reiniciado da mesma forma entre jobs.

Os defaults incluem temperature_inc=0.2f; whisper_sample_token() usa std::discrete_distribution sobre decoder.rng quando há amostragem. Avançar o RNG no fallback pode explicar uma sequência determinística de textos diferentes entre chamadas. É **hipótese sustentada pelo código**, não causa já reproduzida nesta revisão.

no_context=true, threads=1 e core==JNI não a eliminam. Testar consumo de RNG/temperaturas, controle sem fallback e reseed diagnóstico antes de investigar alocação/alinhamento ou recarregar o contexto inteiro.

## R3-02 — Ownership ainda não cobre a topologia real

Produção usa WhisperSessions.shared. Parte dos testes usa controllerA/controllerB separados. No singleton, cancel() lê a geração atual compartilhada, sem receber o identificador capturado pelo solicitante: evento atrasado de A pode cancelar B.

O nativo guarda apenas um slot g_cancel_requested/g_cancel_owner. Um cancelamento de B pode sobrescrever o pedido de A ainda não consumido. Ter owner no slot não equivale a preservar intenções independentes.

transcribeOwned() encaminha owner ao transcribe_impl(), mas a região que toma g_mutex não verifica g_ctx_owner == owner. O teste de transcribe obsoleto demonstra filtro Kotlin, não recusa na fronteira nativa. ActiveTranscribeGuard é instalado antes de leitura WAV e lock; um waiter pode atribuir o owner ativo antes de estar executando.

ensureModelLoaded() retorna true pela chave local quando o modelo coincide. Nova geração abre sessão, mas esse retorno rápido não transfere owner nem confirma contexto existente. Com checagem nativa correta, essa reutilização precisa ser corrigida junto, ou jobs sucessivos passam a falhar; hoje um release da geração nova pode virar no-op.

onDestroy() não invalida a geração no controlador; sem nova sessão, eventos antigos podem continuar atuais. Há posts finais da Activity sem gate de geração. beginSession() agenda prewarm e imediatamente chama a detecção lazy de API: carga ainda pode cair na UI, limitação reconhecida pelo comentário.

W02/W09/W10 tiveram melhorias parciais. Exigir testes com um singleton, cache reutilizado e owners capturados, além da checagem nativa após espera.

## R3-03 — Live: fim prematuro e áudio perdido

Após awaitFinished(60000) devolver false, runLiveMicLoop() apenas registra log e chega ao finally, que zera liveMicPipeline. A thread leitora morre; evaluateLiveMicStop() observa liveMicThread, não LivePcmWorker, e pode anunciar “finalizada” com inferência ativa.

A fila descarta o chunk mais antigo. O teste prova perda de seis chunks sob carga; na UI, resultado com ok=false/vazio é omitido e os descartes aparecem só em log. Contar perda em log não satisfaz integridade nem sobrecarga visível.

produced/captured são incrementados pelo mesmo feed, e expected=captured: igualdade interna não mede perda anterior à captura. Leituras ímpares são ignoradas, e erro/zero da fonte precisa terminal explícito. A fonte injetada deve ter total/hash independente.

Cancelar entre o check e o callback, ou depois de um post entrar no looper, também exige invalidação no momento da entrega. Exceção de listener pode matar a thread antes de marcar terminal. Acrescentar regressões causais e executar s11 em ART; ele ficou não executado.

## R3-04 — enc[0] não é a primeira operação

fase-c/DIVERGENCIA-C2.md mostra mel bit-idêntico e saída FINAL do encoder diferente, começando no índice 0. Isso restringe a fronteira investigada; não identifica a primeira OP defeituosa.

Floats bit-diferentes em todos os elementos podem ocorrer por precisão/ordem de cálculo. Tolerância, referência e replay com entradas iguais são necessários. Vulkan tem falha semântica forte; OpenCL precisa investigação própria. Logits de passos seguintes com tokens livres divergentes deixam de ter entradas comparáveis.

A próxima rodada deve capturar por fronteiras/nós e destilar o primeiro operador com entradas iguais, em vez de repetir dump apenas da saída final.

## R3-05 — Fallbacks p/h não são migração CPU/GPU

SUMMARY-DE interpreta cinco fallbacks por run como operações desviadas de OpenCL para CPU. No código local, n_fail_p conta falhas de logprob threshold e n_fail_h conta falhas de entropy threshold; whisper_print_timings() imprime esses valores como fallbacks p/h.

São contadores de decodificação/fallback por temperatura, não do scheduler GPU. Retificar a explicação causal de W12. A reprodução de vazio continua válida, mas a causa deve ser rastreada por execução real de nós e logits.

## R3-06 — Recuperação Vulkan não cobre toda falha de pipeline

supports_op() removeu a recusa global e trata a flag FA. Falhas não-FA ainda incrementam pipeline_failures, mas não têm a mesma recuperação específica demonstrada. Verificar seleção/despacho de shader obrigatório falho, null ou não compilado.

Preflight usa um caso sintético limitado e cacheia veredito; não prova todas as variantes/shapes/máscaras/strides. Testar variante não coberta e falha não-FA, preservando buffers residentes sem voltar a recusar tudo.

Manter a correção RAII e testar concorrência/permits. Não estender “cadeia inalcançável” além das condições ensaiadas.

## R3-07 — Alcance da prova ART e estabilidade

WhisperLabActivity herda diretamente de Activity e implementa cenários próprios. Ela valida ART/JNI e alguns contratos, mas não automaticamente o cache, os botões e os posts da WhisperActivity real. Exigir Activity real no pacote isolado ou coordenador único comprovadamente usado pelos dois caminhos.

As 30 células iniciaram processo novo e mediram call2. Não cobrem F-01 com contexto reutilizado. Vinte load/release CPU não equivalem a alternância de backends. Processo sem erro não prova qualidade.

D1 GPU não tentado, D2 port não implementado, s11 não executado e small/turbo não avaliados continuam pendências. Plano futuro não substitui implementação solicitada; fases parcialmente entregues não recebem PASS integral.

## R3-08 — Não-fala e bytes inválidos

Silêncio, tom e ruído produziram anotações com status ok. Avaliar VAD/probabilidades com controles de fala fraca/curta: não filtrar texto por colchetes/palavras nem perder fala para aprovar silêncio. O relatório contradiz a disponibilidade do modelo VAD entre seções; conferir inventário.

Sanitização UTF evita abortar ART, mas não corrige os tokens incoerentes. Para 0xB3 CPU, conferir bytes por token, concatenação e fronteiras de segmentos antes de atribuir “latin-1 do modelo”; token byte-level isolado pode ser fragmento de sequência válida. Preservar bytes originais e contagem de substituições.

## Prioridade da rodada 3

1. Testar RNG/fallback e corrigir independência de arquivos.
2. Completar ownership/cache/cancelamento e live na rota real.
3. Localizar/corrigir o primeiro nó divergente Vulkan FA-off e recuperação não-FA.
4. Executar prova numérica FA nas GPUs e implementar o port OpenCL focal.
5. Corrigir não-fala/bytes; então repetir velocidade e estabilidade com contextos reutilizados.

Não trocar a investigação GPU por política CPU permanente. A próxima ordem pede execução e provas, sem integração ou publicação.
