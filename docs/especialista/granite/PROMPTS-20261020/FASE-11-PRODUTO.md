# Ordem F11 — Integração no SIG, capacidades e downloads

Revisão 20261020.1, condicionada ao parecer F10 com perfis selecionados. Meta: tornar as rotas aprovadas utilizáveis no app normal, preservar os downloads e mostrar o que realmente acontece. Não publicar release nesta fase.

## Entradas e limites

Ler AGENTS/MODULE-MAP, contrato comum, mapa de consumidores e parecer F10. Preservar c7d241b e as correções atuais de cache/progresso. Usar arquitetura por estágio; UI não passa a conter transformação numérica nem toda a política de modelo.

O plano quer seis rotas, mas a UI só pode oferecer execução disponível. Para uma rota ainda bloqueada, exibir motivo ou mantê-la experimental de modo explícito, sem simular resultado CPU como NPU/GPU. Não trocar o modelo/idioma silenciosamente.

## Tarefas obrigatórias

1. **F11.1 — Capability registry.** Resolver por família de modelo, dispositivo/ABI/SoC/driver, versão/pacote, grafo/precisão/bucket e contratos. Consumir limites F10. Diferenciar suportado, requer download, experimental e indisponível com motivo. Invalidar somente capacidades afetadas por mudança de runtime/modelo.
2. **F11.2 — Configuração de execução.** Representar CPU/OpenCL/Vulkan/NPU/SmartOC/SmartVK e plano de estágios observado sem enum monolítico enganoso. Smart fixa encoder HTP e editor GPU correspondente, projector explícito. Opções de qualidade, backend e janela são dimensões compatíveis, não sinônimos.
3. **F11.3 — UI e diagnóstico.** Menu com nomes claros, estado de instalação e motivo de indisponibilidade. Relatório/diagnóstico informa modelo, rota efetiva, janela, chunks e fallback. Modo estrito de testes falha; modo produto pode oferecer fallback previsto com mensagem compreensível. Evitar detalhes de kernels/SDK na jornada normal, mantendo-os no diagnóstico técnico.
4. **F11.4 — Áudio longo e 60 s.** Conectar planejador F03 e política de janela por perfil. Arquivo maior é segmentado automaticamente; duração total não se confunde com janela. Progresso/cancelamento/parcial/retomada, erros de formato/espaço e saída final devem funcionar na tela real.
5. **F11.5 — Inventário completo de downloads.** Mapear todos os componentes desta integração: modelo/qualidades/encoder/projector/editor, vocab/frontend/embeddings, ORT/QAIRT e nativos GPU, contextos gerados localmente e qualquer componente compartilhado afetado. Separar bytes baixados de compilação local. Se o downloader for compartilhado com outras ferramentas, incluir seus consumidores reais na regressão.
6. **F11.6 — Persistência e integridade.** Cache por versão/hash/ABI e conjunto coerente. Testar qualidade A→B→A e backend A→B→A, reinício do app/aparelho, offline e retorno a perfil anterior sem novo download dos arquivos válidos. Compartilhar blobs idênticos sem duplicar e sem remover perfis anteriores. Arquivo parcial/corrompido não vale cache; falta de um componente não obriga baixar os demais novamente.
7. **F11.7 — Instalação atômica e recuperação.** Staging de geração, verificação de SHA/tamanhos/contrato, ativação única do conjunto e rollback para geração íntegra. Interromper em download/extração/ativação, cancelar, retomar, simular espaço insuficiente e atualizar runtime/modelo incompatível. Não deixar mistura de versões nem apagar cache válido para “consertar”.
8. **F11.8 — Tela de download coerente.** Para cada evento, download_id/component_id/file_id e bytes reais. Nome/status da linha e título correspondem ao arquivo ativo; itens aguardando/concluídos diferenciados. Total não inventa Content-Length nem mistura arquivo comprimido com extraído. Exibir verificando/extraindo/compilando quando for essa a operação; erro/retry/cancelamento não deixam spinner ou 100% falso.
9. **F11.9 — Ciclo de vida e recursos.** Trocar tela/rotacionar/background/reabrir/cancelar/novo arquivo/mudar perfil e falha de runtime sem corromper resultado ou concorrência. Reservar um proprietário de sessão/operation ID e liberar recursos. O usuário não precisa conhecer threads/cache para transcrever.
10. **F11.10 — Aceitação no app real.** Build integrado no UID normal em aparelho reservado: áudio público curto/longo, cada rota oferecida, fallback, qualidade A→B→A offline, cache corrompido e cancelamento. Comparar métricas com harness F10 para detectar custo de produto. Testar CPU/NAR e TurboCTC e consumidores compartilhados tocados; não expandir para provedores remotos sem necessidade.

## Aceitação

- As rotas oferecidas executam os estágios indicados, ou fallback é explicitamente relatado.
- Não volta a baixar arquivos íntegros ao retornar à qualidade/backend anterior.
- Lista e título do download refletem os mesmos eventos reais e fases corretas.
- Arquivo longo termina completo, com política de janela validada; parcial não vira sucesso.
- Instalação/cancelamento/recovery preservam conjuntos íntegros, sem misturar famílias ou áudios.
- Unitários relevantes, lintDebug, assembleDebug, KDoc/MODULE-MAP e prova funcional do hotspot quando tocado. Não alegar que debug recompilou os nativos.

## Entrega

RELATORIO-FASE-11/report.json/coverage.csv, patch por seam/UI, matriz capability, casos de regressão e capturas da UI, logs/events de downloads com requests/bytes, manifests de gerações, aceitação funcional e métricas no app.

Separar concluído de pendente de pacote nativo/release. Devolver ao especialista para F12. Não publicar, assinar ou promover artefatos.
