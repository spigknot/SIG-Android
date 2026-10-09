# FFmpeg: retomada, avisos e relógios — 2026-10-09

## Comportamento implementado

- Cortar, extrair áudio, girar vídeo, juntar mídias, inserir áudio e limpar áudio registram opções, entradas e etapas concluídas. Ao reabrir a ferramenta, o usuário pode retomar a tarefa preservada.
- A retomada reaproveita somente saídas concluídas com tamanho e data de modificação intactos. A etapa interrompida é reiniciada; arquivos parciais não são aprovados como peças prontas.
- A limpeza automática de cache protege tarefas pendentes. Cancelamento solicitado pelo usuário e tarefas já salvas não são oferecidos como interrompidos.
- A conversão final de container preserva o intermediário e participa dos checkpoints. As opções e os nomes destinados ao usuário são restaurados.
- Divergências na conferência de uma montagem concluída produzem aviso persistente e permitem visualizar/salvar/compartilhar. Cancelamento, falha de execução e ausência de saída completa continuam sendo erros.

## Correções de mídia

- Preroll negativo/descartado não encerra a busca de keyframes nem elimina o keyframe visível em zero.
- FPS nominal diferente não exige recodificar o corpo do segundo vídeo. Em empate de perfil, as emendas usam a taxa predominante dos vídeos compatíveis.
- SmartCut usa PTS/DTS reais, corta por quadros visíveis, preserva VFR e repara apenas o GOP final quando há referência descartada necessária. Busca MP4 imprecisa pode repetir somente a cópia por índice de pacote.
- O áudio contínuo do SmartCut termina no fim escolhido. A validação confere os pacotes e decodifica janelas nas bordas; não decodifica todo o vídeo longo.
- Bordas MediaCodec usam zero B frames, pois o FFmpeg 6 não fornece DTS confiável com B frames nesse encoder. O remux alinha o atraso da borda ao corpo copiado.
- Smart Insert considera a diferença entre início da faixa e início do container; aceita metadados Skip Samples no CSV e reconstrói apenas os pacotes parciais das extremidades. Arredondamento ALAC de até um milissegundo no EOF usa a mesma pequena cauda/padding da recodificação contínua.
- AAC/MP3/Opus e outros codecs sem emenda confiável conservam a política de recodificação contínua de áudio com motivo claro. Os codecs lossless continuam aproveitando cópia parcial.

## Evidência

- Suíte Android completa: **625 testes, zero falhas/erros/skips**.
- Depois do ajuste final de B frames: **38 testes focados**, incluindo SmartCut nativo no host, planejamento de SmartJoin e persistência/retomada, passaram.
- `:app:lintDebug`, `:app:assembleDebug`, `check-module-map.ps1 -Json` e `git diff --check` passaram. O lint mantém avisos existentes; não há erro de lint.
- Smart Insert com ALAC editado: comparação PCM byte a byte com encode contínuo em **0, 0,2 e 0,5 s**, usando cópia no corpo, passou.
- No aparelho autorizado, em processo isolado, usando FFmpegKit/FFmpeg 6 e o código do APK candidato: H.264 com edit list (**80 quadros**) e trecho HEVC/VFR da câmera com GPU (**95 quadros**) mantiveram cada PTS esperado e decodificaram todos os quadros.
- Os dois processos foram encerrados depois de preparar três peças; ao executar novamente, **cinco etapas** (duas bordas e três peças) foram reutilizadas. Só a montagem final foi executada novamente.
- A prova nativa não instalou o APK, não encerrou o SIG do usuário e não testou manualmente cada diálogo de retomada das seis telas. A integração das telas foi revisada e compilada; o mecanismo e os comandos foram exercitados em testes reais.

Logs e resultados completos ficam em `build/`, fora do Git. O APK de teste está em `build/ffmpeg-hardening/sig-ffmpeg-20261009-retomada.apk`; SHA-256 `f31ca8dc6af1a9471b30bb382640618854e4ed4dc0b44fe59820bded94d67d44`. Nenhuma release foi publicada.
