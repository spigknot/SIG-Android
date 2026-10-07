================================================================
RESUMO DA NOITE — 06→07/10/2026 (sessao autonoma autorizada)
================================================================
Bom dia! Tudo pronto. O que foi feito enquanto voce dormia:

----------------------------------------------------------------
1. FIX DO ANR (a prioridade #1) — RESOLVIDO E VALIDADO
----------------------------------------------------------------
- Achei a causa raiz: a traducao segura um "cadeado" interno (g_mutex)
  durante load/geracao, e a tela (thread principal) tentava ler os dados
  pelo MESMO cadeado -> travava -> qualquer toque durante a traducao =
  "ANR" -> o Android fechava a ferramenta (era exatamente o que te
  incomodava: "voltou para a tela anterior").
- Corrigi com um mecanismo novo (cadeado separado so para a UI + cache
  atomico das threads): a tela nunca mais espera o processamento.
- PROVA no seu aparelho (com a lib corrigida instalada):
  * ANTES (v1.508): 3 ANRs reproduzidos no cenario "tocar durante".
  * DEPOIS: dei 10 toques durante DUAS traducoes -> ZERO ANR, app estavel,
    as duas traducoes completaram ("4.2s" e "2.3s", ~37 tokens/s).
- No caminho, meu proprio patch teve um bug (deadlock) — o TESTE pegou
  antes de qualquer release; corrigido e re-validado. Transparencia total
  no relatorio.
- Os 3 gates do projeto passaram com o fix: testes, lint e build (RC=0).

----------------------------------------------------------------
2. PROBE APP-UID (a pergunta "o app normal consegue usar a NPU?")
----------------------------------------------------------------
- Construi um app de teste SEPARADO (npuprobe; nao toca no SIG) com as
  libs do NPU e rodei no aparelho.
- RESULTADO: o app normal NAO consegue acessar o driver DSP/FastRPC
  (o device bloqueia por politica do sistema - ColorOS), mesmo a lib
  estando "publica". No shell/adb (com root) funciona — a diferenca foi
  PROVADA com um teste de controle (liblog carrega OK; cdsprpc nao).
- Traducao: a NPU pelo CLI FUNCIONA (tudo que medimos), mas integracao
  em app normal precisaria de caminho oficial do fabricante. Isso fecha
  a pergunta em aberto do especialista.
- App de teste DESINSTALADO ao final (limpeza).

----------------------------------------------------------------
3. COMO ESTA TUDO AGORA
----------------------------------------------------------------
- Seu telefone: FRIO (20°C bateria), sem lixo instalado, SIG rodando com
  a lib corrigida (o fix esta ativo — o app ficou MELHOR; backup guardado
  se quiser voltar).
- O patch do ANR esta no FONTE do projeto (nao commitado — voce decide).
- PROMPT DETALHADO PARA O ESPECIALISTA pronto (com logs embutidos):
    docs/especialista/PROMPT-ESPECIALISTA-R5-ANR-E-APPUID.txt
- Relatorio tecnico completo: docs/especialista/RELATORIO-ANR-E-APP-UID-NPU.txt
- Detalhes/plano/evidencias: docs/npu-hymt2/rodada4/

----------------------------------------------------------------
4. O QUE PRECISA DA SUA DECISAO (formulario no fim do PROMPT)
----------------------------------------------------------------
1) Aprovar o fix do ANR para commit/pacote oficial? (pronto, testado)
2) Manter ou restaurar a lib no telefone? (recomendo manter)
3) Probe: investir em caminho OEM ou arquivar?
4) Proximos passos sugeridos no prompt.

Nada foi publicado/commitado sem sua autorizacao expressa para tal.
================================================================
