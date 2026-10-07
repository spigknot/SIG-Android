# PLAN-RODADA3.md — R3 (consolidar prova, peso produto, hibrido expandido)

Escopo: ISOLADO (sem deploy/commit/APK/libs/defaults). Base: parecer do especialista.

## A/B — Retificacoes + auditoria read-only  [OK offline]
- [x] RETIFICACOES-R2.md (A1-A8 com evidencia; inclui confirmacao do lifecycle
      g_ctx/g_model no llama_jni.cpp do SIG e MD5-vs-SHA do G1)
- [x] trials-valid.csv (47 validos; classes: 5 INVALID_DOZE, 1 SUPERSEDED, resto OK)
- [x] SHA-256 do corpus novo registrado (RETIFICACOES-R2.md A6)
- [ ] dumpsys/telemetria A3 (pendente: device desconectado as 10:45)
- [ ] sha256 dos outputs (substituir o MD5 do G1 numa recaptura curta)

## C — Testes focados no device (prontos em r3-tests.sh)
- [ ] C3: MUL_MAT q4_K / q6_K no HTP0 (numerico)
- [ ] C1: Q4_K_M produto e2e (short/medium) HTP vs CPU (wall real; settings
      iguais; sem comparacao com app - fora de escopo)
- [ ] C2: baseline app SIG (NAO executar sem autorizacao de acesso ao app)

## D — Hibrido v5 (candidato #1; pronto)
- [ ] htp/cpu/hybrid @ Np 128/512 (Q4_0 label; Q4_K_M se KV compat)
- [ ] n_p_eval do destino (deve ser 1 = ponte), export/import/wall por fase

## E/F — Decisao + relatorio
- [ ] PROMPT-RELATORIO-NPU-HYMT2-RODADA3.txt (A-H) em docs/especialista/

## Bloqueio atual
- Device desconectado (10:45). Pedir reconexao USB/wireless ao usuario para C/D.

## Progresso (atualizado)
- [x] A/B: retificacoes + CSV + corpus SHA + G2 (SHA-256 identico HTP/CPU: 3e16ae75...)
- [x] dumpsys A3: mState=ACTIVE/mLightState=ACTIVE; sem USB; tz 27-28 C (r3-dumpsys.txt)
- [x] C3: MUL_MAT q4_K 103/103 e q6_K 55/55 PASS no HTP0
- [x] C1: short HTP 981,6/26,7; medium HTP 1832,3/27,2; CPU redos:
      short 33,5/35,5; medium 128,0/19,0 (apos stayon USB; primeiro par throttled descartado)
- [x] D np128 (3 modos): streams identicos; n_p_eval_dst=1; state 8,6MB/131pos;
      timers completos (ver relatorio)
- [~] D np512/1024: re-rodando com v5 corrigido (n_batch dinamico) apos
      incidente de permissao/versao do binario (resolvido: chmod + scp do correto)
- [ ] Fechar relatorio R3 (E/F) + resposta final

## Incidentes registrados (R3)
1. Queda do Tailscale (app morto pelo Android em background) -> USB adotado
2. Script device-side v2 morreu no meio (provável LMK) -> voltou-se ao runner via USB
3. Push sobrescreveu binario sem chmod (permission denied) -> chmod reaplicado
4. scp do binario corrigido nao rodou durante as quedas -> binario antigo rodou
   no device -> abortos np512 (rc=134); resolvido trazendo+pushando o correto (63912 B)

## FECHAMENTO R3 (09:20)
- [x] D completo: matriz np 132/528/1034 x htp/cpu/hybrid (v5 corrigido 2x:
      n_batch dinamico + modo htp no ctx correto); HTP-all vence todos.
- [x] C1 final: HTP 981,6-1832,3 pp; CPU 33,5-128 pp (USB+stayon).
- [x] Relatorio final em docs/especialista/PROMPT-RELATORIO-NPU-HYMT2-RODADA3.txt
- [x] stayon USB revertido; nada do SIG alterado.
- Pendencia declarada: C2 (baseline app Vulkan) aguarda autorizacao de acesso.
