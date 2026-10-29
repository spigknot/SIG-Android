# Ordem F12 — Prontidão, reprodução e rollback

Revisão 20261020.1, condicionada ao parecer F11. Preparar uma entrega concreta e auditável. Assinatura, publicação e promoção aguardam aprovação explícita conforme AGENTS; não usar o pedido de plano nem o dossiê como essa aprovação.

## Tarefas obrigatórias

1. **F12.1 — Consolidar a base.** Identificar commits/patches aceitos e pendências. Integrar somente unidades revisadas em base limpa/coerente, sem mudanças de outras tarefas. Registrar HEAD, diff final, versão e configuração efetiva.
2. **F12.2 — Determinar o tipo de entrega.** Comparar NativeDependencyManager.kt e contrato nativo com a base. Se for somente APK, confirmar e reutilizar ZIPs publicados. Se runtime/JNI/bibliotecas/ABI/pacote mudaram, produzir versão experimental coerente e executar build/verify previstos no AGENTS. Não regenerar todos os ZIPs sem necessidade.
3. **F12.3 — Verificar nativos quando aplicável.** Ler scripts/native-dependencies README e usar a versão concreta definida para esta entrega. Comandos existentes de referência: scripts/build-android-native-dependencies.ps1 -Version <versão concreta> e scripts/verify-native-dependencies.ps1 -Version <versão concreta> -OutputDir native-dependencies/build. Substituir o marcador por valor real; não alterar contrato/URL de produção antes da revisão. Verificação precisa corresponder aos bytes que serão propostos.
4. **F12.4 — Gates finais.** Unitários, lintDebug e assembleDebug na base integrada; testes de contratos/ferramentas e harness afetados; module-map e KDoc. Se houver commit autorizado, respeitar gate staged e hook sem bypass. Falha de base fica evidenciada, não apagada do relatório.
5. **F12.5 — Manifesto de entrega.** APK, modelos/external_data, nativos/.skel, contexto quando distribuível, configuração, licenças e avisos por ABI/dispositivo. SHA/tamanho/origem/revisão e contratos. Contexto específico de SoC/driver não pode ser anunciado universal. Verificar dependências e permissões de redistribuição.
6. **F12.6 — Instalação limpa e upgrade de teste.** Em aparelho reservado/probe apropriado, conferir conjunto limpo e migração de versão, cache existente A→B→A, indisponibilidade de componente, offline e rollback. Não apagar produção para simular instalação limpa. Build de teste precisa ter proveniência dos nativos efetivamente carregados.
7. **F12.7 — Smoke e regressão final.** CPU obrigatório, cada rota oferecida, áudio curto/longo, janela 60 apenas aprovada, cancelamento/fallback e UI de download. Repetir comparações F10 somente se a integração mudou materialmente seu fator. Registrar versões exatas e limites restantes.
8. **F12.8 — Plano de rollback.** Geração/pacote anterior íntegro, compatibilidade de preferências/modelos, troca de ponteiro segura e modo CPU. Exercitar falha de nova geração e recuperação, incluindo dados de download/checkpoint sem mistura. Sem downgrade silencioso de artefato por URL.
9. **F12.9 — Documentação de produto e técnica.** Explicar modos suportados e motivos de indisponibilidade, como arquivos longos são tratados, janela preferida/máxima, espaço/primeiro uso, diagnóstico e recuperação. Atualizar ESTADO/DECISOES/índice com evidências e matriz final; nenhum número sem origem.
10. **F12.10 — Proposta de promoção revisável.** Preparar lista dos arquivos concretos, hashes, destinos previstos, mudanças de versão/manifesto, resultados e rollback. Identificar claramente ações pendentes de aprovação: assinatura, upload/publicação e promoção conjunta APK+nativos quando necessária. Não executá-las nesta ordem.

## Aceitação

- Artefatos e testes se referem à mesma configuração/bytes, sem sucesso herdado de build anterior.
- Pacote nativo aceito quando contrato mudou; em APK-only não há regeneração desnecessária.
- Upgrade/cache/rollback e smoke no app normal provados no escopo disponível.
- Matriz diz quais das seis metas foram atingidas e quais continuam sem suporte; investigação encerrada não equivale a rota pronta.
- Plano de promoção concreto permite decisão informada sem reconstruir a entrega.

## Entrega

RELATORIO-FASE-12/report.json/coverage.csv, revisão final/patch, logs dos gates, delivery-manifest, licenças, métricas/smoke, instruções reproduzíveis e rollback testado. Listar o que pode ser aprovado agora e qualquer bloqueio real restante.

Encerrar para parecer do especialista e decisão humana de promoção quando aplicável. Não fazer push, release ou upload por inferência.
