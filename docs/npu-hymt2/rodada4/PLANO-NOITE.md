# PLANO-RODADA4/5 — sessao noturna autonoma (autorizacao ampla do usuario)
## Objetivo ao acordar: TUDO pronto + relatorio/prompt completo p/ especialista c/ logs

## Escopo (parecer R4) e estado
1. [ ] Retificacoes relatorios (residual!=prefill; doze; projecoes; nao-default)
2. [ ] ANR root-cause completa (source audit) -> diff proposto -> APLICAR (autorizado)
       -> testes unit/lint/assemble -> build APK debug -> install (SO se assinatura
       compativel; NUNCA desinstalar o app do usuario) -> teste E2E tap-durante
3. [ ] Auditoria TZ0 (label/units/limites reais)
4. [ ] Probe app-UID (APK separado br.gov.sp.pcsp.npuprobe; ops sinteticas no HTP
       com UID normal; sem root; sem modificar o SIG)
5. [ ] Relatorio A-H + PROMPT ESPECIALISTA (com logs) em docs/especialista/

## Regras ativas (do parecer + repo)
- NAO commit/push (deixar pronto; usuario decide). NAO release/default change.
- NAO desinstalar SIG do device (dados/modelos do usuario preservados).
- Device via USB (Tailscale morre com LMK). Manter stays original (stayon=original).
- Sem root/SELinux/governor/thermal bypass. Sem soak novo.
- Logs redigidos; nada de credenciais.

## Checkpoints
- [inicio] 04:37 autorizacao ampla; plano criado

## FECHAMENTO DA NOITE (07/10 ~02:35)
- [x] ANR: root cause + fix + INCIDENTE do deadlock (detectado pelo E2E) +
      correcao + RED-GREEN no device + gates (test/lint/assemble RC=0).
- [x] Probe app-UID: construido, buildado (probe-min sem OpenCL), executado:
      RESULTADO = bloqueio ambiental (app nao dlopen libcdsprpc.so; controle
      liblog OK; shell funciona). App de teste desinstalado.
- [x] Relatorio A-H: docs/especialista/RELATORIO-ANR-E-APP-UID-NPU.txt
- [x] PROMPT AO ESPECIALISTA: docs/especialista/PROMPT-ESPECIALISTA-R5-ANR-E-APPUID.txt
- [x] RESUMO-DA-NOITE.md (para o usuario)
- [x] Device limpo: probe desinstalado; stayon revertido; temps frios (20-25C).
- [x] Lib do device: MANTIDA com o fix (documentado; backup disponivel).
- Decisao pendente do usuario: commit do fix / restaurar lib / rota OEM.

## Como restaurar a lib original (se decidido)
adb shell "am force-stop br.gov.sp.pcsp.launcher"
adb shell "cp /data/local/tmp/libsig_llama_v11_orig.so /data/data/br.gov.sp.pcsp.launcher/no_backup/native_dependencies/11-arm64-v8a/lib/libsig_llama.so && chmod 755 <mesmo path>"
(sha esperado: 596aba0e9813f516cb68117e9b8f26fe40458010cf86fb118f7c80fac13200d9)
