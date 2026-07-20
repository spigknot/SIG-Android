# Testes NPU - notas de implementação

## Arquitetura encontrada

- Aplicativo Android Views/XML, pacote `br.gov.sp.pcsp.launcher`.
- AGP 8.13.0, Kotlin 2.0.20, Java 17, Gradle 9.0 milestone 1, compile/target SDK 35 e minSdk 24.
- NDK 27.2.12479018, CMake 3.22.1, ABIs `arm64-v8a` e `x86_64`.
- `whisper.cpp` vendorizado em `app/src/main/cpp/whisper.cpp`, versão declarada 1.8.4. Não há metadados Git no diretório que permitam determinar o commit exato.
- O alvo nativo estável `sig_whisper` compila CPU/OpenMP, OpenCL Adreno e Vulkan no mesmo binário. A seleção ocorre em `WhisperNative.loadModel(modelPath, backendKind, flashAttention)`.
- `backendKind`: 0 CPU, 1 OpenCL e 2 Vulkan. O contexto é criado em `whisper_jni.cpp` com `whisper_context_default_params()` e `use_gpu`.
- Áudio é convertido na Activity para WAV PCM s16le, mono, 16 kHz. O JNI valida esse formato antes de entregar amostras float ao Whisper.
- Modelos normais ficam em `getExternalFilesDir("whisper_models")`. A tela atual possui importação e download simples, mas não hash nem retomada HTTP.
- Cancelamento usa flag atômica e `abort_callback`; segmentos, progresso e logs retornam por callbacks JNI.

## Ponto de integração do encoder

O `whisper.cpp 1.8.4` possui caminhos internos de encoder externo para Core ML e OpenVINO. `whisper_encode_external()` faz o pipeline reservar `embd_enc`, e a implementação externa escreve nesse tensor antes do decoder. Essa é a referência menos invasiva para uma futura integração QNN.

A API pública expõe `encoder_begin_callback`, mas esse callback apenas permite abortar antes do encoder; ele não injeta embeddings nem pula por si só o encoder GGML. Portanto, uma integração real exige um patch experimental guardado por `WHISPER_QNN_ENCODER_EXPERIMENTAL`, inspirado nos caminhos Core ML/OpenVINO, ou uma API upstream equivalente.

## Isolamento adotado

- Feature flag `BuildConfig.ENABLE_NPU_TESTS`.
- UI e Kotlin em `experimental/npu`.
- Biblioteca nativa separada `sig_npu_probe`; o caminho normal não a carrega.
- Pacotes em `getExternalFilesDir("npu_models")`, separados dos GGML.
- Manifesto embutido sem URLs inventadas. Tiny, Base e Turbo permitem importação local validada.
- QNN é apenas sondado dinamicamente. Uma biblioteca encontrada não equivale a HTP inicializado.

## Estado atual e limitações

- Diagnóstico Android, Vulkan, memória, armazenamento, térmico e sondagem dinâmica QNN: implementado.
- Inicialização oficial do provider/backend HTP: bloqueada pela ausência local do QAIRT SDK, headers e binários redistribuíveis.
- Encoder QNN: não executado.
- Decoder Vulkan estável: preservado, mas ainda não conectado à saída QNN.
- Híbrido: desabilitado. Não existe fallback silencioso.
- Downloads remotos: o manifesto suporta URL, mas o manifesto embutido não oferece download porque não há artefato oficial compatível configurado.
- Os pacotes precisam trazer `package.json`, artefatos e SHA-256. Um GGML comum não é aceito como encoder NPU.
- Os artefatos do AI Hub podem ser específicos de chipset/runtime. Não se presume que um context binary seja universal.
- As páginas atuais do AI Hub descrevem versões edge com MHA substituída por SHA e camadas lineares por convoluções. Esses artefatos não podem ser presumidos numericamente compatíveis com o decoder GGML do checkpoint original sem comparação explícita. Além disso, as páginas consultadas listam atualmente Snapdragon X/Compute, não confirmam o aparelho Android alvo.

## Riscos

- Layout/dtype/quantização do encoder incompatíveis com `embd_enc` do decoder.
- Checkpoint/tokenizer divergentes entre encoder e GGML.
- Restrições do linker namespace Android ao carregar bibliotecas vendor.
- Context binary incompatível com geração HTP ou versão QAIRT.
- Custo de cópia/desquantização apagar o ganho do encoder.
- RAM e aquecimento, especialmente no Turbo.

## Próxima integração real

1. Instalar QAIRT autorizado e definir `QNN_SDK_ROOT`.
2. Exportar Tiny e compilar para o SoC Snapdragon de teste.
3. Integrar headers QNN apenas no alvo experimental e inicializar provider, HTP backend, device, context e graph.
4. Confirmar HTP por profiling oficial.
5. Comparar numericamente a saída do encoder com a referência.
6. Adicionar caminho externo QNN ao fork do Whisper sob macro experimental.
7. Escrever uma única vez no tensor `embd_enc` e manter o decoder no Vulkan.

## Rollback

Desative `ENABLE_NPU_TESTS`, remova o card/Activity e o alvo `sig_npu_probe`. Nenhum arquivo do fluxo normal de modelos ou da engine `sig_whisper` precisa ser revertido.

## Referências oficiais

- https://github.com/ggml-org/whisper.cpp
- https://github.com/qualcomm/qidk
- https://aihub.qualcomm.com/compute/models/whisper_tiny
- https://aihub.qualcomm.com/compute/models/whisper_base
- https://aihub.qualcomm.com/compute/models/whisper_large_v3_turbo
- https://aihub.qualcomm.com/apps/whisper_kit_android
- https://docs.qualcomm.com/
