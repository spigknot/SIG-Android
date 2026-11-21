# Seletores da tela Whisper

O seletor de motor dá prioridade à chave `whisper_engine_cpp` e lê `whisper_engine_ort` quando a chave atual está ausente. Sem escolha, o motor continua sendo Onnx RT. A escolha de threads usa `whisper_threads` (1–8, padrão 4) e preserva a migração de `whisper_ort_threads`.

Onnx RT oferece CPU, NPU→CPU e NPU. No SM8550 admitido, com turbo e pacote v3 verificado, NPU é o padrão sem preferência explícita. O pacote v2 verificado pode servir NPU→CPU quando escolhida, com os decoders CPU recebendo as threads do job. Essa mesma rota pode usar o encoder do v3. Em aparelho/modelo incompatível, ambas as opções NPU permanecem no menu com o motivo. Quem já usa ORT e tem o v3 continua com aceleração automática no turbo até escolher outra rota. A seleção de CPU é persistida. Abrir a tela nunca oferece nem inicia download.

Whisper.cpp oferece CPU, OpenCL e Vulkan, com a guarda nativa de carga preservada para Vulkan e para OpenCL em Adreno A7xx com modelos large/turbo. Flash Attention continua desligado por padrão. A prova de contexto da R14 registrou `flash_attn=off` em `auditoria-rodada14/evidence-observed/fase-d/device/d-ctx-1500-terminal.txt:4`. A prova da R24 registrou `flash_attn=on` em `auditoria-rodada24/device-recovered/fulltext__sD-ui_final-terminal.txt:4`. Como o histórico tem braços com ambos, foi mantido o padrão anterior.

VAD fica em Nenhum até escolha explícita e guarda modo e nível no arquivo de preferências do Whisper. A rota ORT em `e5f2381` entregava o PCM original ou convertido diretamente ao motor, como na R117, `evidencias-rodada117-2026-11-20/T02/logcat-c6.txt:183` e `:255`. Para arquivos e gravação concluída, o filtro PCM usa o mesmo JNI da tela Transcrição. A segmentação do microfone ao vivo tem configuração própria de janelas e continua separada do filtro de arquivos.

## Destino dos controles do painel

| Controle | Destino |
|---|---|
| VAD filter | Seletor VAD, com Nenhum e três filtros com níveis 0 a 3 |
| usar whisper.cpp | Seletor de motor com a mesma chave |
| Ajuda do motor | Rótulos dos motores e motivos apresentados no seletor de hardware |
| Threads, valor e setas | Seletor de 1 a 8 |
| Flash Attention | Grupo Whisper.cpp, visível somente nesse motor |
| Word timestamps | Mesmo controle no grupo Whisper.cpp, padrão desligado |
| Beam size, setas e ajuda | Mesmos controles no grupo Whisper.cpp, padrão de produto 5 |
| Best of, setas e ajuda | Mesmos controles no grupo Whisper.cpp, padrão de produto 5 |
| Redefinir | Removido conforme a ordem. O usuário pode revisar os valores nos seletores e controles acima. |
| Configurações Avançadas | Removido. O grupo Whisper.cpp aparece junto aos demais controles quando o motor está selecionado. |

## Fila futura

- Vulkan — melhorias
- Whisper menores na NPU — qualificação
