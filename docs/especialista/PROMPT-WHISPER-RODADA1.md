# Instruções ao executor — Whisper, rodada 1

## 1. Missão e resultado esperado

Você é o executor da primeira rodada de estabilização e aceleração do **STT (Whisper) local** do SIG Android. O responsável pelo planejamento revisará seu relatório e emitirá a próxima rodada. Execute este plano, investigue os desvios encontrados e entregue correções focais com evidências. Não encerre com uma proposta genérica ou apenas um build bem-sucedido.

Metas do usuário:

1. Acelerar a transcrição ao máximo, começando por testar Flash Attention.
2. Reproduzir e corrigir os problemas intermitentes do Vulkan, verificando também OpenCL.
3. Corrigir outros defeitos encontrados na ferramenta.

Velocidade deve ser medida com qualidade, integridade do áudio e estabilidade preservadas. Modelo menor, menos busca ou fallback para CPU precisam ser identificados como mudanças de configuração; não podem ser apresentados como ganho de Flash Attention.

Leia `AGENTS.md`, `MODULE-MAP.md`, `native-dependencies/README.md` e `docs/whisper/ANALISE-INICIAL-2026-10-08.md`. O runbook `docs/validation/STT_ACCEPTANCE.md` trata STT **remoto**: respeite os cuidados de dispositivo/credenciais/dados quando aplicáveis, mas não execute APIs remotas nem trate aquele runbook como teste funcional do Whisper local.

A rodada termina com:

- Runner reproduzível e corpus de teste identificado.
- Baseline observado e comparação candidata, inclusive todas as falhas.
- Correções focais comprovadas, ou causa/experimento mínimo bem delimitado quando ainda não for possível corrigir.
- Matriz funcional e de desempenho com `PASS`, `FAIL`, `SKIP_JUSTIFICADO` ou `NAO_EXECUTADO`; ausência de recurso não é teste verde.
- Relatório completo em `docs/whisper/rodada1/RELATORIO-WHISPER-RODADA1.md`.
- Patch/commits limitados ao Whisper; artefatos de teste e dispositivo com estado final documentado.

Não prometa estabilidade universal. Informe exatamente quais modelos, arquivos, drivers, aparelhos e repetições foram testados.

Prioridades para administrar o tempo: P0 = proveniência/runner, reprodução do Vulkan e contratos de lifecycle/cancelamento; P1 = FA pareado, parser WAV, diagnóstico OpenCL e reteste das correções; P2 = varredura de decoder/threads e modelos adicionais. Complete P0/P1 com os recursos disponíveis. Se uma investigação exigir muitas horas, deixe checkpoint com caso mínimo e evidências; reduza P2 antes de perder o relatório. Não reduza um teste de regressão diretamente ligado à correção. O objetivo desta rodada é gerar informação confiável para a próxima, não explorar todas as combinações possíveis de uma só vez.

## 2. Isolamento, escopo e trabalho concorrente

Há outro agente mexendo em outras partes do app. Commits e arquivos fora do escopo podem surgir. Preserve-os e prossiga a partir da base registrada; não os reverta, incorpore como suas correções nem investigue suas funcionalidades. Nunca use `git add .`, reset/clean destrutivo, stash global ou amend de commit alheio.

Antes de editar:

1. Registre `git status --short`, `git rev-parse HEAD` e hashes dos fontes focais.
2. Use checkout/worktree isolado com branch `codex/whisper-r1` ou nome livre equivalente. Reutilize um worktree apropriado se já existir; fixe explicitamente o ref/base observada. O padrão de uma ferramenta de criação pode usar o remote default branch: não aceite isso sem verificar a base.
3. Configure dependências locais nesse checkout sem copiar segredos para o relatório. Não rode clean no checkout que o outro agente usa.
4. Use diretórios próprios para CMake, APK, runner, corpos de logs e corpus. Nunca reutilize uma .so do build de outro agente como candidata sem manifesto completo.
5. Ao editar arquivo compartilhado, preserve os hunks alheios. Se houver conflito no mesmo trecho Whisper, compare a versão nova e reaplique somente a mudança focal; registre a adaptação. Commits estranhos fora do escopo não exigem interromper a rodada.

Allowlist principal:

- `app/src/main/java/br/gov/sp/pcsp/launcher/WhisperActivity.kt`
- `app/src/main/java/br/gov/sp/pcsp/launcher/WhisperNative.kt`
- `app/src/main/res/layout/activity_whisper.xml`
- `app/src/main/cpp/whisper_jni.cpp`
- `app/src/main/cpp/whisper.cpp/**`, somente arquivos necessários à causa demonstrada
- Novos seams Whisper, testes focais, runner em `scripts/` ou `tools/whisper/`, testes nativos em `native-dependencies/tests/whisper/` e documentos em `docs/whisper/`
- `MODULE-MAP.md` para novos fontes de produção Kotlin/Java, com KDoc antes da primeira declaração
- Build/manifest de debug somente quando indispensáveis ao runner; mudanças pequenas e guardadas pela variante/propriedade de teste

`TranscriptionReport.kt`, `LittleEndianIo.kt`, `SttOutputStorage.kt` e o loader OpenCL Android são compartilhados. Preferir um seam Whisper; qualquer mudança compartilhada exige prova da necessidade e testes dos consumidores afetados. Não generalize divergências deliberadas documentadas pelo repositório.

Fora do escopo: `RemoteSttActivity.kt`, provedores REST/WS, Hy-MT2, `llama/`, `llama-jni/`, Granite, QNN/NPU, políticas gerais de download, refatoração de todo o app, publicação e release. As duas árvores GGML são distintas: corrigir `llama/ggml` não corrige Whisper. Não copie o patch Vulkan do Hy-MT2 por semelhança de nome.

Nesta rodada não alterar versão/URLs/SHA publicados de `NativeDependencyManager.kt`, nem republicar ZIPs, assinar release ou promover APK. Compilação e pilotos locais são validação; a promoção futura terá aprovação específica do usuário e verificadores nativos.

## 3. Ambiente: confirmar antes de testar

O planejador observou em 08/10/2026:

- Fonte base `963d41a08d78a1a456b8c7b59a5a025e1fd2e238`.
- Whisper vendorizado declara `1.8.4`; isso não identifica o commit upstream nem assegura que a .so instalada foi produzida desses fontes.
- Componentes declarados v11. O APK normal não recompila nativos.
- `C:\adb\adb.exe`; **ADB autorizado principal: `100.114.88.45:5555`**, disponível continuamente conforme o usuário. Conexão confirmada, estado `device`, `run-as` funciona. `PJA110`, SoC `SM8550`, Android 13 / API 33; SIG 1.508 / versionCode 71, debuggable.
- GPU Adreno 740; device Vulkan API 1.3.128 (raw 4206720), driverVersion raw 2150252544, driver Qualcomm build `1a285a84ae`, compiler Vulkan `E031.41.03.36`. O compiler/driver OpenCL ainda deve ser consultado pela API OpenCL.
- Arquivo instalado `no_backup/native_dependencies/11-arm64-v8a/lib/libsig_whisper.so`: 36.168.384 bytes, SHA-256 `6213f86e95732b568ec7f3b3e3bbabab7cc0a7978f4c62b4b4fd1ffecd0414d2`. Confirmar novamente antes do baseline; hash do arquivo instalado ainda não é prova de carregamento.

Essas leituras são uma fotografia. Reconfirme. Se o usuário fornecer outro serial, aparelho, modelo ou cenário, use a informação mais recente e registre-a. Informações antigas de CPH2747/Adreno 840 pertencem a outros testes e não caracterizam o PJA110.

**Autorização já concedida:** executar os testes locais via ADB no PJA110; o usuário abriu a porta do adbd e informou que o aparelho estará disponível o tempo todo. Não pedir nova confirmação para conectar, compilar, instalar o aplicativo de ensaio isolado e executar os testes reversíveis descritos. Preservar o SIG instalado e dados do usuário conforme o isolamento/rollback. Usar corpus sintético/controlado; não fazer gravação incidental nem usar áudios pessoais. Essa autorização não é aprovação de release/publicação.

Confira também respostas posteriores sobre o sintoma do Vulkan. Sua ausência não bloqueia a rodada: execute o baseline e as reproduções descritas, identificando quais falhas você observou sem presumir que são o relato original.

Conexão inicial no PowerShell:

```powershell
$whisperAdb = 'C:\adb\adb.exe'
$whisperSerial = '100.114.88.45:5555'
& $whisperAdb connect $whisperSerial
& $whisperAdb -s $whisperSerial get-state
& $whisperAdb -s $whisperSerial shell getprop ro.product.model
& $whisperAdb -s $whisperSerial shell getprop ro.soc.model
```

Verifique os exit-codes e a identidade PJA110/SM8550. Se a rede cair, fazer reconexão limitada (até duas tentativas), registrar interrupção e retomar com run-id novo; não tratar reconexão como continuidade de medição válida. Não reconfigurar o adbd/firewall nem fechar a porta aberta pelo usuário. A sessão USB `1164a04` foi observada, mas não deve ser escolhida implicitamente nem contada como segundo aparelho. O runner deve usar o serial de rede explicitamente por padrão, permitindo override informado.

Ao usar ADB após a conexão, sempre especifique `-s <serial>`. Não executar simultaneamente benchmarks concorrentes no mesmo aparelho. Se o outro agente estiver usando-o, prepare trabalho independente e use outra janela; não force-stop o processo dele, mate seu ADB ou troque seus nativos.

Colete apenas propriedades técnicas necessárias: modelo/SoC/ABI/API, GPU e versão do driver Vulkan, nome e compiler/driver OpenCL, RAM total, thermal status, versão do app e do componente. Não arquive indiscriminadamente getprop completo com identificadores pessoais.

### Manifesto de proveniência

Crie `build/whisper-rodada1/<run_stamp>/run-manifest.json`, no checkout isolado. Incluir:

- Base Git, branch/worktree, diff focal e hashes SHA-256 dos fontes relevantes.
- JDK/Gradle/NDK/CMake, ABI, flags de compilação e diretório real de build.
- SHA-256/tamanho do APK e de cada .so usada, especialmente `libsig_whisper.so` e `libomp.so`.
- Caminho e hash da lib realmente carregada no dispositivo, PID e maps quando disponíveis.
- Origem do componente estável e da candidata; dependências preservadas e possíveis diferenças entre árvore local e artefato distribuído.
- SHA/tamanho/hiperparâmetros/quantização do modelo; SHA/forma/duração do WAV, fonte/licença ou texto TTS conhecido.
- Configuração pedida e configuração efetiva: backend, FA, estratégia de decode, beam, best-of, threads, VAD, timestamps e perfil Vulkan.
- Ordem/seed dos casos, limites de timeout, estado térmico e tempos.

Não concluir que a candidata foi testada porque apenas `assembleDebug` passou. Prove que seu SHA foi carregado. Se não puder verificar maps por permissão, use caminho carregado + hash lido no sandbox + identidade compilada; informe a limitação.

## 4. Leitura focal e achados a verificar

As linhas podem deslocar. Busque as funções, confirme o comportamento e classifique cada item como `ESTATICO`, `REPRODUZIDO`, `CORRIGIDO` ou `HIPOTESE`.

### W01/W02 — lifecycle e ownership (prioridade alta)

- `onDestroy()` chama `WhisperNative.releaseModel()` na thread principal.
- `transcribe()` e `releaseModel()` seguram/tomam `g_mutex`; a inferência é longa.
- `g_ctx` e `g_cancel_requested` são globais; `modelLoadLock` e `currentlyLoadedModelKey` são de cada Activity.

Reproduza inferência ou load ativos, saída da tela, nova instância e cleanup atrasado. Prove quem possui cada contexto. Não corrija apenas colocando `releaseModel()` numa Thread solta: a Thread antiga poderia liberar o contexto recém-criado. Use serialização com ownership/run-id/generation e cleanup vinculado à sessão; nenhum lock de inferência nem join deve ser aguardado pela UI. Um seam testável pode manter estado e agendamento; a Activity conserva UI. Preserve o contrato JNI existente sempre que possível, documentando qualquer extensão necessária.

Invariantes: uma operação nativa de sessão por vez; cancelamento não espera o mutex; callback e cleanup antigos não mudam a nova sessão; liberar contexto apenas após término do uso e apenas pelo owner; sair/reabrir continua responsivo.

### W03/W04 — gravação e live (prioridade alta para reproduções)

- `stopLiveMicTranscription()` faz join de até 3 s, descarta a thread e reabilita a UI sem verificar término.
- `runLiveMicLoop()` lê e transcreve no mesmo worker; a inferência suspende a drenagem do AudioRecord.
- `processLiveChunk()` força beam/best-of 1, VAD/timestamps off; FA/backend vêm do contexto. Não atribua esse ganho de decoder a FA.
- `transcribeRecordedWav()` não segue todo o estado ocupado/cancelamento usado nos arquivos; alguns callbacks live silenciam logs/erros.

Teste stop enquanto a inferência demora mais de 3 s, captura com erro de leitura, erro de load, negação de permissão e tentativa de iniciar trabalho novo. Finalizado significa worker encerrado e recursos liberados, não apenas join expirado. O status específico de erro/cancelamento deve sobreviver ao cleanup.

Para captura, use fonte PCM injetável em teste: blocos numerados com ordem/offset conhecidos, consumidor deliberadamente lento. Conte bytes capturados/processados/descartados e filas. Se confirmar perda estrutural, separar captura e inferência com fila limitada e política explícita de sobrecarga. Não ocultar perda nem permitir fila sem limite. Operar mais rápido que tempo real exige RTF sustentado <1; se o hardware/modelo não cumprir, reportar limite. Não implantar um redesenho amplo de streaming antes de fechar ownership e medir a falha.

### W05 — Vulkan

`configure_vulkan_memory_limit()` atualmente:

- Bloco de subalocação 256 MiB; allow sysmem fallback e prefer host memory ligados.
- Async, coopmat/coopmat2, FP16, BF16 e integer dot desligados.
- Graph optimize e fusion habilitados por `unsetenv`.
- Remove overrides de allocation/buffer/memory limit.

Audite quais variáveis o backend local realmente lê e quando as lê. Diversas opções usam presença de `getenv`: definir `"0"` pode continuar DESLIGANDO um recurso. Para habilitar, remova a variável. O backend/cache pode sobreviver à liberação do modelo; comparar perfis no mesmo processo sem prova de reinicialização invalida o A/B. Configure o ensaio antes de qualquer consulta que possa enumerar/inicializar GPU, inclusive diagnóstico. O perfil efetivo deve vir do estado/capacidades usados pelo executor, e não apenas de um echo das variáveis solicitadas.

As variáveis de ambiente são globais ao processo e podem alcançar outras libs GGML. Ensaios de perfis ficam na variante de teste/processo dedicado; não mude o comportamento de outras ferramentas.

Falha de driver, `SIGSEGV`, `GGML_ABORT`, `SIGABRT` e device-lost não são recuperáveis por um `catch(std::exception)` genérico. Registre stack/tombstone e a etapa. Não remova asserts de segurança nem devolva transcrição vazia como sucesso. Invalidar/liberar o contexto de forma consistente para erros recuperáveis; não continuar usando um dispositivo perdido.

### W06 — OpenCL / Flash Attention

O fonte local compila variantes FA para múltiplas dimensões durante `ggml_cl_init`, inclusive FP32/FP16, mesmo com FA off. `supports_op` anuncia FA conforme dimensão/tipos. Identifique custo de compilação e ponto de crash.

Referência primária para investigação focal: https://github.com/ggml-org/whisper.cpp/blob/d1be6fde11ac6e0407606b4e42fe72d34add8037/ggml/src/ggml-opencl/ggml-opencl.cpp . Nessa revisão existem guardas para compiladores Adreno E17 e A7x com variantes mistas. O driver real ainda precisa ser identificado. Não aplicar guarda por nome genérico do aparelho ou importar o backend completo. Comparar detecção, compilação e anúncio de suporte: recusar a operação depois de já compilar o kernel perigoso não elimina o crash de inicialização. Qualquer fallback deve indicar motivo e execução efetiva na CPU.

Se confirmar a falha, um port focal pode evitar compilação da variante insegura, compilar sob demanda ou devolver unsupported para aquela combinação/driver, com regressão positiva/negativa. Meça o impacto: fallback de atenção pode piorar throughput. Não mascare como aceleração GPU.

### W07 — WAV e fronteira JNI

`read_wav_mono_16k()` usa `chunk_size` para alocar e não confere todos os `fread`. Examine EOF parcial, `fmt` curto, chunk fora do arquivo, número ímpar de bytes, padding, data/fmt em ordem diferente e múltiplos data chunks. Não aceitar amostras inexistentes preenchidas por resize. RIFF válido com chunks desconhecidos deve continuar funcionando; formatos não suportados têm erro explícito.

Corrija com limites contra o tamanho real restante, overflow, leitura exata e RAII. Evite um limite arbitrário pequeno que quebre áudios longos válidos. Trate erro de alocação/arquivo na fronteira JNI antes de escapar para Java; evite segurar memória duplicada desnecessariamente, medindo depois. Diferencie parser Kotlin e parser nativo para garantir aceitação coerente.

### W08/W09 — callbacks e cancelamento

`CallbackState` conserva `JNIEnv*`; callbacks de logs podem vir de outras threads e o estado de logging é global. Instrumente thread-id/GetEnv e duração da inscrição. Se confirmar callback em outra thread, obter JNIEnv da thread atual via JavaVM, attach/detach apenas quando necessário, usar referência global com vida definida e proteger estado compartilhado. Não apenas suprimir o callback que acusa erro. Verifique exceção Java, logs paralelos e nenhuma chamada após fim/cleanup.

O cancelamento é resetado no início de `loadModel/transcribe`. Coloque barreiras determinísticas antes da leitura de WAV, antes do lock e antes da inferência; teste cancelamento nesses intervalos. Um pedido da sessão atual não pode ser apagado por uma transição, nem vazar para a próxima. Callback após cancelamento não pode completar uma sessão já terminada.

### W10 e inventário secundário

Medir overhead de terminal/UI, sobretudo `appendTerminal()` que reparsa o histórico em cada linha e atualizações frequentes. Preserve diagnósticos em arquivos, podendo usar buffer limitado e snapshots/throttle eficientes na UI. Não usar logs verbose para medir performance final.

Inspecione também modelos corrompidos/importados, controls/busy após gravação, silêncio, timestamps, exportação, limpeza, e texto válido começando por `Erro:`/`Cancelado:` confundido com prefixos de controle do JNI. Corrigir nesta rodada apenas o que tiver reprodução/teste e risco controlado. Itens maiores entram no backlog do relatório com experimento concreto.

## 5. Runner e instalações de teste

Construa `scripts/run-whisper-adb-benchmark.ps1` (ou nome focal equivalente), com argumentos validados. Use o runner Granite existente apenas como referência de manejo ADB/exit-code; ele não testa Whisper e não deve ser alterado.

Contrato mínimo do runner:

- `Serial` (padrão autorizado `100.114.88.45:5555`), caminhos de modelos/corpus/manifesto, `Backends` = CPU/VULKAN/OPENCL, `FlashModes` = off/on, `WarmupRuns`, `MeasuredRuns`, `TimeoutSeconds`, `OutputDirectory`, seleção de cenários e seed.
- SHA e ABI esperados da lib; recusar rodar candidata não identificada.
- Execução com/sem recarregamento de modelo, processo novo para perfis, casos de cancelamento e alternância.
- Cada caso isolado possui run-id e resultado terminal observável. Timeout externo finito; preserve logs antes de encerrar somente o processo de teste.
- Distinguir erro ADB de erro do motor. Erro no stderr normal de push não é falha; exit-code nonzero não pode ser perdido em pipe/Out-Null. Resultado ausente/JSON inválido/callback final não visto é FAIL.
- Log estruturado incremental com fases; flush antes de operações nativas arriscadas. Crash não pode apagar toda a evidência por salvar somente no final.
- Retorno não-zero para falha; resumo com número de testes executados, falhos e pulados.

Preferir app debug isolado com applicationId de benchmark (`br.gov.sp.pcsp.launcher.whisperbench`) e runner/debug Activity ou instrumentação focais, usando o mesmo `WhisperNative` e motor. Guarde a seleção de applicationId/entrypoint por propriedade/variante de teste; confirme manifest mesclado, autoridades FileProvider e ausência do entrypoint no release. Não exponha extras arbitrárias/loader de teste em produção.

O aplicativo de teste deve instalar/carregar o componente estável por caminho validado e depois a candidata, em seu próprio sandbox. `WhisperNative` usa `NativeDependencyManager.loadLibrary`, então não suponha que uma lib empacotada no APK vencerá a lib baixada. Verifique e escolha o mecanismo pelo código real. Não é preciso mudar hashes/URLs do produto para um piloto.

Se for indispensável usar o SIG instalado para reprodução real, faça isso somente na janela/aparelho autorizado, com backup de APK/libs/estado necessário, SHA antes/depois e restauração em finally. Não apague dados nem modelos do usuário. Piloto de .so: validar fonte/ABI/tamanho/hash, push para staging, readback, processo parado antes da troca e backup validado. `native-dependencies/tests/safe_deploy.sh` é referência, não autorização automática nem solução completa de rollback. Não usar redirecionamento binário de PowerShell que corrompa .so.

É permitido reusar build estável identificado. Build nativo candidato deve vir do checkout isolado e realmente reconstruir `sig_whisper`. O comando documentado é:

```powershell
.\gradlew.bat :app:assembleDebug -PbuildNativeComponents=true
```

Se puder compilar apenas alvo/ABI focal com o arranjo CMake existente, registre o comando completo e dependências. O build comum, separado, continua sendo gate do app. Não executar `build-android-native-dependencies.ps1` apenas para produzir uma .so de ensaio: esse script também envolve componentes alheios. Não gerar/publicar ZIPs nesta rodada.

## 6. Corpus e referência de qualidade

Prepare fixtures sob `build/whisper-rodada1/<run_stamp>/corpus`; binários, modelos e áudio ficam fora do Git. Versione somente gerador/manifesto não sensível e textos sintéticos de referência.

Corpus mínimo:

| Caso | Entrada | Finalidade |
|---|---|---|
| C01 | Fala pt-BR limpa 10–20 s, WAV 16 kHz mono s16, referência conhecida | Sanidade e reprodução rápida |
| C02 | Fala pt-BR 60–120 s, >=200 palavras, referência conhecida | Qualidade e desempenho principal |
| C03 | Fala de 5–10 min, com pausas | Memória, repetição e sustentação térmica |
| C04 | C02 com ruído determinístico moderado e seed fixa | Sensibilidade de VAD/decode |
| C05 | 2–5 s de silêncio, tom e ruído separados | Entrada sem fala e erro controlado |
| C06 | Fala curta com prefixos “Erro:” e “Cancelado:”, números e pontuação | Colisão texto/status e integridade |
| C07 | Mesma fala convertida para estéreo/48 kHz e arquivo comprimido aprovado | Preparação/conversão e equivalência |
| C08 | RIFF inválido, fmt curto, EOF truncado, data acima do arquivo, tamanho ímpar, padding, chunks desconhecidos | Parser sem OOM/overread/crash |
| C09 | PCM injetável numerado; chunks perto de 2 s e sobra <0,5 s | Integridade live e finalização |

Use TTS pt-BR local disponível ou corpus público autorizado/licenciado com referência. Se C03 for fala curta concatenada, indique que serve a carga/duração, não a diversidade linguística. Não gerar silêncio/tom e chamá-los de teste de precisão STT. Guarde contagem de amostras e duração exata antes e após VAD.

Modelos: começar por `base` multilíngue presente, depois `small` ou o modelo efetivamente usado na falha. Incluir `large-v3-turbo` se disponível e memória/capacidade permitirem. Tiny auxilia sanidade e lifecycle, mas não substitui o modelo problemático. Identificar cada quantização pelo arquivo/header/hash; não inferir pelo nome. Sem modelo/recursos, indicar a célula pendente e concluir o restante.

Qualidade: texto de referência humana/sintética é ground truth; CPU FA-off é controle adicional, não verdade absoluta. Calcular WER/CER com normalização documentada (caixa/pontuação/espaços) e conservar diferença bruta. Evitar normalização que apague números, nomes ou trechos perdidos. Usar o mesmo WAV/modelo/decoder nos pares. VAD tem que ser medido separadamente e não pode cortar fala para parecer rápido.

## 7. Ordem de execução da rodada

### Etapa A — baseline sem correções de comportamento

1. Inventariar fontes, artefato estável e autorização/recursos.
2. Executar testes unitários focais existentes de seams utilizados (p. ex. LittleEndianIo, TranscriptionReport, SttOutputStorage). Inspecionar os nomes reais das classes; não fingir teste Whisper inexistente.
3. Implementar runner/diagnóstico guardados para teste. Medir que não mudam parâmetros/motor; manter origem estável distinta da candidata recompilada.
4. Reproduzir o cenário fornecido pelo usuário. Na ausência de sintoma preciso, executar C01/C02, seis combinações backend x FA e os testes de saída/cancelamento.
5. Cada combinação: um cold load real; um warmup excluído; três inferências medidas com contexto reutilizado. Ordem alternada ou randomizada com seed, respeitando cooldown térmico. Não comparar CPU fria com GPU aquecida.
6. Se o baseline crashar, preservar o crash como resultado. Duas reproduções iguais com evidência bastam para direcionar a investigação; não repetir a mesma falha perigosa sem nova hipótese.

Se o fonte local divergir do produto instalado, manter três identidades: B0 = artefato distribuído identificado; B1 = rebuild do fonte-base sem as correções; C1 = build corrigido com mesmas flags de B1. Comparar B1/C1 para atribuir efeito ao patch, e B0/C1 para medir efeito para o usuário. Não misturar mudança de fonte, flags, strip e patch numa única comparação. Se B0 não puder ser reproduzido, declarar essa limitação.

### Etapa B — regressões determinísticas e correções prioritárias

Criar testes focalizados no comportamento, não testes que só repetem a implementação:

- Lifecycle: fake controlável por latches/barreiras; load/inferência/cleanup presos fora da UI; heartbeat na UI. Verificar cancel/sair rápido e nova sessão intacta após callback/cleanup antigo.
- Para o teste nativo de mutex, observabilidade de aquisição real. Sleep presumindo lock adquirido não é prova. Hooks somente em build de teste, worker com limite e cleanup verificado. Se hook indisponível, SKIP explícito, acompanhado de teste real; não verde fictício.
- WAV: um teste por fronteira relevante, tamanhos grandes declarados com arquivo pequeno, um WAV válido com chunk desconhecido/padding e um truncado. ASan/UBSan no host quando disponíveis; testar JNI no dispositivo porque o teste do parser Kotlin não cobre C++.
- Cancel: antes de inferência, entre leitura/lock e depois de iniciado, duas solicitações, seguido de nova sessão bem-sucedida.
- Gravação/live: stop com worker >3 s, permissão/erro de load/read, controles liberados somente após cleanup, callback atrasado ignorado.
- Parser de resultados/runner: processo abortado, timeout, JSON incompleto, hash errado e backend ausente precisam produzir falha observável.

Corrigir W01/W02/W03/W07/W09 conforme demonstrados. Investigar W08. Para W04, medir/injetar primeiro, executar correção focal se o ownership estiver fechado; caso exija outro desenho, apresentar patch/plano separado e o teste que falha. Cada correção precisa de reprodução antes, resultado depois e controle de não-regressão.

Não mudar defaults de qualidade/performance ainda. Respeitar os três modos da ferramenta; uma correção apenas para arquivos não fecha lifecycle da gravação/live.

### Etapa C — prova de Flash Attention e localização de falhas GPU

Primeiro provar operação e tipos reais. `flash_attn=1`, devices enumerados e tempo menor não demonstram kernel GPU executado. Inserir diagnóstico de agendamento/contagem e, quando necessário, contador na entrada de execução de FA Vulkan/OpenCL, só na variante diagnóstica. Registrar quantos nós foram executados em GPU/CPU e as razões de recusa; não alegar cobertura de todo o grafo por observar um único nó.

Há operações legitimamente na CPU, como preparação e eventualmente VAD. Não exigir que todo o produto execute integralmente em GPU. A prova numérica de um kernel GPU deve executá-lo sem fallback; o benchmark do produto pode ter grafo misto, desde que isso fique identificado e a comparação seja correta.

Faça testes numéricos nativos pequenos contra controle CPU e/ou atenção convencional de referência, com os mesmos operandos arredondados/tipos e sincronização explícita:

- Operação `FLASH_ATTN_EXT`, dimensão de head 64 e dimensões reais coletadas do modelo, Q FP32, K/V FP16; casos FP32/FP32 do OpenCL quando exercitados.
- Queries 1, 5 e múltiplas linhas; KV 31/32/33, 63/64/65 e contexto próximo de 1500 quando representativo; stride/view/permutation realmente usados pelo Whisper.
- Sem máscara (encoder/cross), máscara FP16 causal do decoder, múltiplas heads; pelo menos quatro seeds.
- Casos analíticos: Q=0 -> média de V entre posições permitidas; V constante -> saída constante. Nenhum NaN/Inf ou saída fora da propriedade esperada.
- Se FA off também falhar, testar a operação suspeita do caminho comum (`MUL_MAT`, softmax, conversão/view, etc.) com formato real, antes de mexer nos kernels.

Registrar max_abs, RMSE, erro relativo/norma e todas as falhas. Como início para entradas limitadas e controle com os MESMOS tipos: allclose FP32 atol=1e-4/rtol=1e-3; caminho com precisão FP16 atol=3e-3/rtol=3e-3. Esses limites são de investigação, não licença para relaxar até passar. Divergência acima do limite fica FAIL/INVESTIGAR; documente efeito da quantização antes de propor outra tolerância. Não usar apenas correlação/cosseno, igualdade de texto curto ou "não crashou" como aceitação numérica.

Criar repro mínimo por falha, com shape/dtype/seed/driver/lib. Verificar layout, memória de subalocação, limites de descriptor/workgroup/shared memory, sincronização/transferência e precisão da acumulação. Backport upstream só com SHA/linhas/dependências e prova daquele repro; sem atualização integral do whisper.cpp nesta rodada.

### Etapa D — A/B Vulkan com uma variável

Comparar primeiro os perfis de proteção, sempre com processo novo e perfil efetivo registrado:

| Perfil | Delta contra V0 |
|---|---|
| V0 | Configuração atual, referência |
| V1 | Somente fusion desligada |
| V2 | Somente graph optimize desligado |
| V3 | Somente preferência de host memory retirada |
| V4 | Somente bloco de subalocação menor, se OOM/alocação apontarem para isso |

V1/V2 prioritários se houver texto errado, NaN/erro numérico; V3/V4 condicionais à evidência de memória/transferência. Não rodar todos os perfis sem necessidade. Para cada perfil relevante: FA off/on em C01/C02 e caso de falha, base + modelo problemático, três repetições após warmup; avaliar qualidade e estabilidade.

Só depois de encontrar configuração correta/estável, ensaiar recurso de velocidade isolado: V5 = FP16 permitido; V6 = async permitido. Coopmat/BF16/dot não são prioridade da rodada 1; não ligar tudo de uma vez. Execute teste numérico + smoke antes de benchmark. Reverter o perfil que falhar; localizar causa. Não impor mudança global para todos os fabricantes por um único driver.

Se o driver aborta o processo, limite o caso ao processo de ensaio. Logs de validação/perf/shader ficam em execução diagnóstica separada; medições finais usam perfil sem debug pesado.

### Etapa E — reteste e carga sustentada

Escolha V0 e o melhor perfil correto; mantenha CPU e OpenCL como controles. Executar a matriz abaixo. Casos unsupported ou sem capacidade são registrados; nunca substituir modelo/parâmetro silenciosamente.

| Família | Cobertura mínima na rodada |
|---|---|
| FA + throughput | CPU/Vulkan/OpenCL x off/on, C01/C02, base e modelo problemático; cold-load e contexto reutilizado |
| Repetição | 30 inferências curtas no mesmo contexto para Vulkan off/on; 10 ciclos completos load/transcribe/release para cada combinação disponível |
| Alternância | 10 sequências CPU -> Vulkan -> OpenCL -> CPU, com load/transcribe/release; FA off/on/off e modelo A -> B -> A, 5 vezes cada |
| Lifecycle | Cancel em load/inferência/preparação, Back/sair/reabrir e stop-live; pelo menos 5 repetições nos cenários corrigidos |
| Arquivos | Arquivo único, lote de pelo menos 3, WAV pronto, conversão C07, URI inacessível, saída sem permissão/espaço simulado |
| VAD/timestamps | VAD off/on, word timestamps off/on, FA off/on em modelo/base estáveis e C02; combinar os dois toggles pelo menos uma vez |
| Carga longa | C03, pelo menos 3 execuções no perfil escolhido e controles viáveis; registrar aquecimento e memória durante a execução |
| Entradas adversas | C05/C08 e modelo inválido/ausente; erro ou no-speech definido, sem crash/loop/memória excessiva |
| Gravar/live | Fonte injetada sempre; microfone real somente autorizado; integridade de duração/chunks, cancelamento e finalização |

Arquivos e microfone são caminhos distintos; não declarar live validado por transcrever um WAV. Word timestamps atuais usam `token_timestamps`, e o contexto padrão tem DTW desligado; não bloquear a opção só porque DTW é incompatível com FA. Verificar comportamento real e monotonicidade/limites dos timestamps.

Se uma classe de teste falhar, interrompa a carga daquela candidata, reproduza e corrija; preserve o resultado falho. Depois repetir a célula e controles afetados. Não desperdiçar bateria/tempo repetindo células já verdes sem nova mudança. Intermitência exige repetição, alternância e evidência de estágio; nenhuma repetição finita garante ausência total de falhas.

Para falhas intermitentes, conservar o denominador: zero em N testes independentes admite, aproximadamente, limite superior de 3/N para a taxa de falha com 95% de confiança. Testes correlacionados no mesmo contexto/aparelho não satisfazem automaticamente essa independência. Trinta runs sem falha são triagem, não aceitação longitudinal; propor na rodada seguinte o soak e a diversidade de drivers que faltarem.

### Etapa F — desempenho de decoder/threads, separadamente

Somente no perfil que passou qualidade/estabilidade, explorar beam size 5/3/1 e threads 2/4/6 (ou número menor coerente com núcleos do aparelho). Variar um eixo por vez em C02; best-of só no caminho em que efetivamente é usado, incluindo fallback por temperatura se houver. Beam 1 não deve ser descrito automaticamente como estratégia greedy.

Preservar modelo/quantização e referência. Produzir opções com tradeoff explícito de WER/CER/tempo. Não reduzir o padrão para maximizar apenas um número. Se ainda houver falha principal aberta ou custo excessivo da matriz, entregar baseline desses eixos e próximo experimento; não atrasar o relatório para uma varredura sem critério.

## 8. Métricas e critérios de aceitação

Por execução salvar JSON/JSONL e CSV locais, com IDs e referências no relatório:

```text
run_id, caso, repetition, cold_or_warm, device, driver, sha_lib, sha_model,
sha_audio, backend_requested, backend_effective, flash_requested,
flash_gpu_nodes_executed, flash_cpu_nodes_executed, fallback_reason,
vulkan_profile_effective, sampling_strategy, beam, best_of, threads,
vad, word_timestamps, audio_original_s, audio_after_vad_s,
copy_ms, convert_ms, load_ms, vad_ms_if_available, encoder_ms,
decoder_ms, inference_wall_ms, teardown_ms, ui_to_done_ms,
peak_pss_mb_if_measured, rss_samples, thermal_before_after,
cancel_request_to_worker_done_ms, result_status, native_error,
WER, CER, transcript_diff_path, diagnostics_path
```

Não inventar métricas ausentes. `dumpsys meminfo` com amostragem mede pico observado, não pico exato. PSS após liberar pode incluir cache do driver; crescimento merece série temporal e instrumentação, não uma conclusão automática de leak.

RTF = inference_wall_s / duração **original** do áudio. Informar também RTF pós-VAD, se útil, e latência total incluindo load/conversão. Speedup pareado = tempo de controle / tempo candidato. Resumir mediana, mínimo/máximo e número de falhas; com >=10 amostras, acrescentar p95. Não calcular p95 significativo de apenas 3 medidas nem excluir timeout/crash do resumo.

Gate de estabilidade da candidata: zero crashes/ANRs/device-lost/callbacks após término/double-free nas células executadas; UI responde durante load/inferência/cleanup; estado terminal correto; próximo run funciona após cancelamento/falha recuperável. Meta de teste de responsividade: heartbeat/cancel/sair sem bloqueio >500 ms em cenário determinístico; latência de cancelamento do worker registrada à parte (GPU pode não interromper operação no meio). Finalização nativa longa não autoriza travar a interface. Se não cumprir, registrar falha e localizar; não mascarar com status prematuro.

Gate de qualidade: nenhuma perda/duplicação de trecho sistemática, NaN/Inf, texto incoerente ou timestamp inválido introduzido; em corpus principal, piora >1 ponto percentual absoluto de WER ou >1 palavra adicional no caso curto exige investigação. Isso é um limite de triagem para a rodada, não promessa de equivalência semântica; conservar os diffs para revisão. No live injetado, integridade de amostras precisa ser exata, salvo política de descarte explicitamente demonstrada (que não pode ser promovida como correção completa de perda).

Gate de velocidade: declarar ganho apenas após pares comparáveis, configurações efetivas comprovadas e qualidade/estabilidade verdes. Ganho menor que a variação entre repetições é inconclusivo. Perfil mais seguro porém mais lento pode ser correção aceitável, mas o custo deve aparecer e a próxima rodada buscar desempenho.

Em silêncio, diferenciar sem-fala esperado de motor quebrado. O código de arquivos atualmente trata texto vazio como erro. Defina um resultado consistente e testado; não declare "corrigido" mudando qualquer erro para sucesso vazio.

## 9. Gates de código e empacotamento

Rodar os testes focais após cada correção relevante. Ao fechar o conjunto de mudanças, executar no checkout isolado e guardar comandos/exit-codes/logs:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
& .\scripts\check-module-map.ps1 -Quiet
```

Executar também os novos testes nativos/harness e instrumentados focais no dispositivo autorizado. Teste instrumentado não rodado não é substituído por unitário. Testar controles existentes de seams compartilhados se alterados. Se modificar o harness central, executar:

```powershell
& .\scripts\tests\validate-agent-harness.tests.ps1 -Quiet
```

O novo runner Whisper deve ter testes próprios para seus contratos, sem alterar gratuitamente o gate central. Não alterar testes antigos para acomodar uma regressão. Falha alheia: comprovar na base/checkout limpo e relatar separadamente; não descartá-la sem evidência.

Uma correção C++ precisa do build nativo e de teste da lib resultante, além dos gates Android. `assembleDebug` comum não é essa prova. Inspecionar dependências ELF/ABI e carregamento; não introduzir `NEEDED libOpenCL.so` onde o loader dinâmico atual evita isso. Registre também efeitos em x86_64: compile/smoke CPU quando viável, mas emulador não valida o driver GPU do telefone.

Ao criar fonte de produção Kotlin/Java, atualizar MODULE-MAP e KDoc. Ao terminar, validar `git diff --check` e allowlist. Commits locais podem separar infraestrutura, correção lifecycle, parser e correção GPU. Sem push/release automático.

## 10. Relatório obrigatório para a próxima rodada

Escreva **`docs/whisper/rodada1/RELATORIO-WHISPER-RODADA1.md`**. Faça o arquivo autossuficiente, com links relativos verificáveis para fontes/documentos e caminhos absolutos dos artefatos locais não versionados.

Estrutura exigida:

1. **Resultado executivo:** o que foi corrigido, se FA acelerou em cada backend, se Vulkan ainda falha, perfil correto mais rápido observado e limites.
2. **Proveniência:** base/final/diff, worktree, dispositivo/driver, APK/.so/modelos/áudios SHA, comandos completos. Diferenciar lib estável, candidata diagnóstica e candidata final.
3. **Relato do usuário e reprodução:** cenários fornecidos; cenário mínimo de cada falha; quando ocorre (load/compile/encoder/decoder/UI/cleanup/export); logs/stack e frequência.
4. **Tabela de achados:** ID, prioridade, ESTATICO/HIPOTESE/REPRODUZIDO/CORRIGIDO, causa, arquivos/linhas, teste que falhava antes e passou depois, risco residual. Mapear W01–W10 e novos IDs W11+.
5. **Matriz completa:** cada célula com PASS/FAIL/SKIP_JUSTIFICADO/NAO_EXECUTADO, contagem e motivo. Separar arquivo/gravação/live; nenhum "todos passaram" sem denominador.
6. **Desempenho:** dados individuais referenciados e tabela mediana/variação/falhas/RTF/speedup, cold/warm, perfil e FA efetivos, memória/thermal, encoder/decoder e overhead. Manter baseline falho visível.
7. **Qualidade:** referências, normalização WER/CER, números, diffs curtos de exemplos sintéticos, perdas/duplicações/alucinações/timestamps e casos não avaliados.
8. **Correções:** explicação de cada patch e motivo; origem upstream com SHA quando houver; teste de regressão; mudanças de comportamento/defaults claramente indicadas; alternativas relevantes e custo da proteção escolhida.
9. **Gates:** comando, exit-code, status e arquivo de log; unitários/instrumentação/nativos/build/lint/map; testes skipped visíveis.
10. **Dispositivo e rollback:** estado inicial/final, restauração verificada, arquivos/apps de ensaio remanescentes e comandos seguros para reproduzir. Se SIG de produto não foi tocado, registrar isso como fato verificado.
11. **Backlog para o planejador:** até 5 próximos experimentos prioritários; para cada um, hipótese, evidência, delta exato, teste, resultado esperado e condição de parar/reverter. Incluir bloqueios de recurso/autorização e cobertura em segundo aparelho/driver.

Salvar junto ao relatório um resumo pequeno de métricas em Markdown e manifesto de correções. Logs brutos, WAVs, modelos, .so/APK e dumps grandes continuam em `build/whisper-rodada1/`, ignorados pelo Git. O relatório deve conter hashes e caminhos suficientes para reabrir as evidências; não entregar só arquivos ignorados que o planejador não localizará.

No encerramento da rodada, retornar os caminhos absolutos do relatório, patch/branch e diretório de evidências, mais um resumo curto de resultados e pendências. Não iniciar a rodada 2 nem publicar artefatos: o planejador analisará este material e fornecerá o próximo prompt.
