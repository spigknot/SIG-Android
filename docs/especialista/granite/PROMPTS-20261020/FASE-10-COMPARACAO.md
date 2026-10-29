# Ordem F10 — Comparação formal e seleção por aparelho

Revisão 20261020.1, condicionada aos candidatos congelados após F09 e ao áudio longo F03. Esta fase decide o que recomendar; não é mais uma exploração com knobs mudando a cada resultado. Se houver correção material, criar revisão de candidato e repetir apenas comparações afetadas.

## Preparação obrigatória

Ler contrato comum e pareceres anteriores. Antes de abrir o conjunto reservado, emitir experiment-plan.json com candidatos/controles, hashes, corpus/normalização, categorias, tolerâncias, endpoints, repetições, ordem/seed, exclusões, critérios e orçamento.

Estimar custo pelo RTF/carga dos pilotos. Qualidade formal pode rodar uma passagem por candidato em todo o corpus; repetições de performance usam subconjunto representativo pré-definido, não cinco repetições automáticas de todos os 30 minutos em seis rotas. Se o orçamento não comportar a bateria, propor divisão F10A/F10B ao especialista antes de coletar e reduzir a alegação ao escopo medido.

## Tarefas obrigatórias

1. **F10.1 — Matriz honesta.** Enumerar CPU, OpenCL, Vulkan, NPU, SmartOC e SmartVK. Para cada uma: disponível/experimental/bloqueada/não comprovada, estágios reais, precisão, janela máxima/preferida, cold/warm e motivo de ausência. Rotas mistas adicionais recebem nome próprio. Não preencher números de rota inexistente com fallback.
2. **F10.2 — Corpus reservado.** Confirmar pelo menos 50 clipes/enunciados, 3000 palavras e 30 min de áudio público/licenciado, sem contaminação da calibração/exploração e sem duplicações para atingir mínimo. Diversidade PT-BR, ritmo/ruído/vozes, frases densas, números/nomes/negação/repetição, silêncios e arquivos longos. Fixar referências e normalização.
3. **F10.3 — Qualidade.** Medir WER/CER micro e por arquivo, I/D/S, deltas pareados contra CPU validada e intervalos por falante/arquivo. Aplicar categoria preregistrada: equivalente +0,5 pp em ambos; intermediário +1 pp; compacto +3 pp WER/+2 pp CER. Investigar todo erro crítico novo atribuível ao candidato, sem relaxar critério após ver o resultado. Se inconclusivo, dizer isso.
4. **F10.4 — Mesmo trabalho.** Comparar rotas com mesmos cortes/áudio/qualidade para atribuir ganho ao hardware. Em braço separado, comparar melhor configuração validada de produto por rota, incluindo janelas diferentes e custo de compilação/ponte/junção. Não misturar os dois rankings.
5. **F10.5 — Performance pareada.** No subconjunto declarado, pelo menos 5 pares válidos por candidato/cenário final; alternar com semente/blocos balanceados e condição térmica comparável. Separar cold-process, cold-context e sessão quente. Exibir valores individuais, mediana, dispersão e incerteza. p95 só com n≥20 e método explícito; n pequeno não sustenta cauda.
6. **F10.6 — Perfil completo.** Carga/compile/frontend/enc/proj/montagem/ponte/editor/CTC/junção/output/total, RTF/RTFx com intervalo definido, memória por evento/pico observado e bytes de transferência. Registrar alimentação/bateria/thermal. Energia em J somente se método adequado existir; caso contrário null/proxy identificado.
7. **F10.7 — Sustentação.** Série de uso contínuo e áudio longo, conforme orçamento e ao menos um soak próximo de 30 min para os perfis recomendados. Comparar início/fim quanto a RTF/memória/thermal, falhas, cancelamento e troca de perfil A–B–A. OOM/SSR/hang não podem desaparecer por exclusão silenciosa.
8. **F10.8 — 40 versus 60.** Nos perfis elegíveis, comparar qualidade, latência, memória e cancelamento de janela 60 s com política menor sobre os mesmos arquivos. Quando 60 não foi aprovada, registrar sem forçar. Definir máximo seguro e janela preferida separadamente.
9. **F10.9 — Pareto e break-even.** Mostrar qualidade × tempo total × memória × estabilidade e custo do primeiro uso. Estimar quantos chunks/arquivos amortizam compilação/cache. Meta de utilidade inicial: ≥15% de redução mediana do tempo total ou ≥20% de redução de memória com latência adequada, sustentada pelos dados; ganho incerto permanece experimental.
10. **F10.10 — Recomendações acionáveis.** Para cada aparelho/driver testado e cenário curto/longo/cold/warm, escolher padrão conservador, perfil qualidade, perfil compacto e fallback, somente onde comprovados. Não extrapolar um Snapdragon para todos os Android. Listar experimentos não concluídos e a menor prova que falta; propor capacidades/limites que a F11 consumirá.

## Auditoria e critérios de passagem

- Todas as tentativas/exclusões constam; tabelas regeneráveis a partir de logs/eventos e hashes.
- Nenhuma recomendação sem qualidade/atribuição de hardware/estabilidade correspondentes.
- Não escolher um resultado entre números contraditórios sem explicar configuração, UID, workload e intervalo medido.
- Quadro mostra também rota funcional sem ganho e rota bloqueada. “Sem vencedora única” é conclusão válida.
- Se candidato alterar código, testar/gate e reconstruir identidade; não continuar usando o SHA anterior.

## Entrega

RELATORIO-FASE-10/report.json/coverage.csv, experiment-plan, corpus/normalização e candidatos congelados, dados brutos/métricas/qualidade/partições, scripts de análise, gráficos estáticos exportáveis quando úteis, tabela de Pareto e recomendação por aparelho/cenário.

Informar o que pode entrar no produto e o que deve continuar experimental. Devolver ao especialista para emitir a F11 com perfis e limites preenchidos; não habilitar tudo porque a tabela ficou completa.
