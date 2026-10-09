# Whisper: análise inicial e direção das rodadas

Data: 08/10/2026. Análise dos fontes; nenhum benchmark ou piloto foi executado nesta etapa.

Base observada: `963d41a08d78a1a456b8c7b59a5a025e1fd2e238`.
O repositório e o dispositivo podem mudar durante as rodadas; cada executor deve registrar a própria base.

## Objetivo

Maximizar a velocidade de transcrição mantendo qualidade e estabilidade; reproduzir e corrigir as falhas do Vulkan; verificar também OpenCL, CPU e os três caminhos da ferramenta: arquivos, gravação seguida de transcrição e microfone ao vivo.

As ordens das rodadas ficam em `docs/especialista/whisper/`, conforme orientação do usuário. O primeiro prompt está em `docs/especialista/whisper/PROMPT-WHISPER-RODADA1.md`; a cópia no caminho original foi preservada para a execução já iniciada. O executor deve devolver evidências e um relatório, não declarar o projeto inteiro concluído a partir de um smoke test.

## O que o código já oferece

- `WhisperActivity.kt` tem Flash Attention em Configurações Avançadas, desligado por padrão. `ensureModelLoaded()` inclui a opção e o backend na chave do contexto.
- `WhisperNative.loadModel()` entrega a escolha a `whisper_context_params.flash_attn`. O whisper.cpp usa `ggml_flash_attn_ext` no encoder, na self-attention do decoder e na cross-attention.
- Há implementações de Flash Attention em CPU, Vulkan e OpenCL. Existência no fonte e checkbox ligado não demonstram execução efetiva na GPU.
- A correspondência atual é **0 = CPU, 1 = Vulkan, 2 = OpenCL**. `docs/NPU_IMPLEMENTATION_NOTES.md` contém a ordem antiga/invertida e não deve orientar o harness.
- O caminho de arquivos usa beam search, beam size 5 e best-of 5 por padrão; o JNI fixa quatro threads. No microfone ao vivo, cada chunk usa beam/best-of 1 e VAD/word timestamps desligados, independentemente dessas escolhas avançadas.
- O JNI já imprime tempos internos do Whisper. É preciso complementar esses dados com fases, backend efetivo, perfis e identidade dos artefatos.
- O build comum do APK não recompila Whisper. `NativeDependencyManager.kt` declara componentes v11; a documentação relata produção de nativos em checkout isolado. Um APK novo pode continuar usando uma lib antiga.

## Achados e hipóteses prioritários

| ID | Evidência nos fontes | Implicação | Prova necessária |
|---|---|---|---|
| W01 | `onDestroy()` chama `releaseModel()` na thread principal; o JNI toma `g_mutex`, também mantido durante a inferência | A saída da tela pode bloquear a interface. É um risco demonstrável pela estrutura de locks, ainda sem reprodução em aparelho nesta análise | Teste com inferência realmente ativa, saída/reabertura e aquisição observável do lock; heartbeat da UI |
| W02 | `g_ctx` e cancelamento são globais; `modelLoadLock` e a chave do modelo pertencem à Activity | Instâncias antigas e novas podem disputar o mesmo contexto; uma limpeza atrasada pode liberar a sessão nova | Sequência sair/reabrir enquanto há load/inferência/cleanup, com IDs de sessão e prova de ownership |
| W03 | `stopLiveMicTranscription()` espera até 3 s, descarta a referência da thread e reabilita controles sem verificar se ela terminou | O usuário pode iniciar trabalho novo enquanto o anterior ainda executa; estado de finalização pode ser falso | Worker artificialmente lento e teste real; confirmar término/cleanup, não apenas expiração do join |
| W04 | A mesma thread lê `AudioRecord` e executa `processLiveChunk()` sincronamente | Enquanto transcreve, a captura não é drenada; áudio pode ser perdido e latência pode crescer | Injeção determinística de PCM numerado e medição de leitura, fila, duração capturada e duração processada |
| W05 | Perfil Vulkan desliga async/FP16/coopmat/BF16/dot, prefere host memory e habilita fusão/otimização | Há margem potencial de desempenho, mas desligar proteções em conjunto impediria atribuir regressões a uma causa | A/B por uma única variável, processo novo e comprovação do perfil efetivo |
| W06 | OpenCL local compila Flash Attention durante a inicialização, incluindo variantes FP32/FP16, sem depender do checkbox | Um erro de compilação pode ocorrer até com FA off; inicialização tem custo relevante | Driver/compiler reais, tempo de build de kernels, stack de falha e comparação focal com upstream |
| W07 | Leitor WAV nativo não verifica todos os retornos de `fread`, nem limita `chunk_size` pelo tamanho restante antes de alocar | Arquivo truncado pode virar áudio preenchido artificialmente ou provocar alocação excessiva; exceções podem escapar antes do try da inferência | Fixtures pequenas com tamanhos inconsistentes, EOF parcial, padding e formatos inválidos; testes nativos sem modelo |
| W08 | `CallbackState` guarda `JNIEnv*`; callbacks de log passam por estado mutável e callbacks Java | `JNIEnv` pertence à thread; há risco se GGML emitir logs em outra thread, a confirmar | IDs de thread/GetEnv, vida dos callbacks e teardown; teste de exceção Java e callback após término |
| W09 | Cancelamento global é resetado no início de `transcribe`; load/inferência/finalização têm contratos diferentes | Um pedido feito no intervalo de preparação pode ser perdido ou uma sessão antiga afetar a nova | Barreiras determinísticas antes de leitura, aquisição de lock e inferência; cancelamento repetido e run seguinte |
| W10 | `appendTerminal()` reparsa o histórico por linha; logs e snapshots são enviados à interface | Instrumentação e UI podem consumir tempo e esconder ganho do motor | Comparar captura técnica sem UI com caminho real; medir taxa de logs e custo do terminal |

Também devem ser inventariados: silêncio tratado como erro de transcrição vazia; validação de modelos importados; estado de controles após gravação; callbacks atrasados; temporários; falhas de exportação; e a colisão entre texto transcrito e prefixos de controle `Erro:`/`Cancelado:`. O executor deve distinguir defeito reproduzido, evidência estática e hipótese.

## Observação específica sobre OpenCL

No upstream consultado, o backend reconhece falhas do compilador Adreno E17 e de variantes mistas de Flash Attention em A7x. Isso justifica verificar o driver real e a compilação local. Não demonstra, por si, que o dispositivo do usuário sofre essa falha, nem autoriza copiar o backend inteiro.

Referência primária fixada para comparação: [GGML OpenCL no commit d1be6fde](https://github.com/ggml-org/whisper.cpp/blob/d1be6fde11ac6e0407606b4e42fe72d34add8037/ggml/src/ggml-opencl/ggml-opencl.cpp). Essa revisão tem diferenças substanciais para a árvore local; qualquer backport precisa de prova focal e dependências explícitas.

## Ambiente observado, sem piloto

Leituras ADB em 08/10/2026, atualizadas após a autorização do usuário:

- **Endereço autorizado e disponível continuamente: `100.114.88.45:5555`.** `adb connect` funcionou e `get-state` retornou `device`. A sessão USB `1164a04` também foi observada; não representa um segundo aparelho.
- Modelo `PJA110`, SoC informado `SM8550`, Android 13 / API 33; `run-as br.gov.sp.pcsp.launcher` funciona.
- SIG instalado `1.508`, versionCode 71, debuggable.
- GPU confirmada por SurfaceFlinger/vkjson: Adreno 740. Vulkan API do device `1.3.128` (raw `4206720`), driverVersion raw `2150252544`; driver Qualcomm, build `1a285a84ae`, compiler informado no **Vulkan** `E031.41.03.36`. Isso não substitui consulta do driver/compiler **OpenCL**.
- Componente instalado sob `no_backup/native_dependencies/11-arm64-v8a`. `lib/libsig_whisper.so`: 36.168.384 bytes; SHA-256 `6213f86e95732b568ec7f3b3e3bbabab7cc0a7978f4c62b4b4fd1ffecd0414d2`.
- Esse hash identifica o arquivo instalado, não prova ainda qual lib foi carregada numa inferência. OpenCL driver/compiler, modelos disponíveis e sintoma intermitente ainda precisam de levantamento pelo executor.

O usuário autorizou os testes via ADB neste PJA110 e informou disponibilidade contínua. Não é necessário pedir novamente essa autorização. Usar áudio sintético/controlado e sandbox de teste; gravação incidental de áudio pessoal fica fora do ensaio. Evitar disputar o mesmo dispositivo com o outro agente. Um segundo aparelho/driver será importante para verificar a generalização das correções.

## Estratégia

1. Registrar a base, lib efetiva e cenário de falha; construir um runner reproduzível.
2. Corrigir primeiro contratos de lifecycle, cancelamento e WAV com testes que reproduzam o problema.
3. Comparar FA off/on mantendo modelo, decoder e áudio iguais; provar em que backend a operação executou.
4. Isolar falhas Vulkan por operação/perfil; corrigir apenas a causa demonstrada.
5. Selecionar os perfis corretos mais rápidos e submetê-los a repetição, alternância de modelos/backends, arquivos longos e uso real da tela.
6. Avaliar qualidade e throughput de outros ajustes, como beam/threads/VAD, em experimentos separados. Velocidade com perda de áudio ou piora material de qualidade não é sucesso.

O primeiro plano é uma rodada extensa, mas delimitada: gera baseline, infraestrutura, correções focais e dados para escolher os próximos experimentos. Zero falhas em uma rodada é evidência apenas dos cenários e dispositivos testados. A decisão sobre o próximo plano depende do relatório, inclusive das células não executadas e das limitações.

## Saída exigida da rodada 1

`docs/whisper/rodada1/RELATORIO-WHISPER-RODADA1.md`, com manifesto de origem, tabela de bugs, matriz preenchida, resultados individuais e agregados, diferenças de qualidade, patches, gates, estado restaurado do dispositivo e próximos experimentos. Áudios, modelos, binários e logs brutos ficam na área de build ignorada; o relatório conserva referências e hashes.
