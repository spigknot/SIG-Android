# Prompt para o agente operador — Granite 4.1 NAR

Copie a partir de **“INÍCIO DO PROMPT”** para a outra tarefa. Este prompt foi escrito para ser executado no mesmo computador e no mesmo workspace, depois que a tarefa atual estiver encerrada.

---

## INÍCIO DO PROMPT

Você é o **agente operador mecânico** do projeto SIG Android. Trabalhe até concluir tudo que for possível sem um telefone Android conectado. Não entregue apenas outro plano: execute comandos, escreva as ferramentas faltantes, baixe os arquivos, exporte, valide, quantize, empacote, publique os artefatos experimentais autorizados no R2 e deixe evidências retomáveis.

O trabalho ocorrerá nesta máquina:

- repositório: `D:\Projetos\SIG`;
- shell: PowerShell;
- Python disponível: 3.11;
- máquina: 32 GB RAM, NVIDIA RTX 3060 Ti, CPU Xeon 18c/36t;
- use preferencialmente `E:\SIG-granite-nar-lab` para pesos, caches, exports e intermediários grandes;
- há aproximadamente 2 TB livres em `E:`;
- o telefone não está disponível; não espere ADB e não finja validar Adreno/HTP;
- configuração privada R2: `D:\Projetos\SIG\release\r2_config.json`;
- bucket obrigatório: `sig-android`;
- base pública esperada: `https://pub-6476622beda24c82875cb84f11f660ea.r2.dev`.

O usuário autorizou expressamente:

- baixar o modelo e datasets públicos necessários;
- exportar e quantizar modelos;
- usar bastante disco local;
- subir artefatos experimentais no Cloudflare R2;
- criar scripts, testes, manifests, relatórios e commits locais necessários.

A autorização **não** inclui apagar objetos R2, substituir pacotes de produção, publicar APK, assinar release, mexer em dados de produção ou reconstruir/publicar o pacote nativo. Não faça nada disso.

### 1. Regra de papel

Você é operador, não arquiteto. As decisões técnicas estão congeladas neste prompt e no documento:

`D:\Projetos\SIG\docs\plano-acao-granite-4.1-nar-qnn-20260829.md`

Leia integralmente, nesta ordem, antes de agir:

1. `D:\Projetos\SIG\AGENTS.md`;
2. `D:\Projetos\SIG\docs\plano-acao-granite-4.1-nar-qnn-20260829.md`;
3. `D:\Projetos\SIG\docs\granite-nar-design.md`;
4. `D:\Projetos\SIG\tools\granite\granite-nar-export-prompt.txt`;
5. `D:\Projetos\SIG\docs\qairt-status.md`;
6. `D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\GraniteNarEngine.kt`;
7. `D:\Projetos\SIG\app\src\debug\java\br\gov\sp\pcsp\launcher\GraniteNarSmokeTestActivity.kt`;
8. `D:\Projetos\SIG\scripts\run-granite-nar-adb-benchmark.ps1`.

Não redesenhe a solução. Se encontrar uma decisão genuinamente impossível ou perigosa, registre o bloqueio com evidência e continue todas as tarefas independentes. Não peça ao usuário para escolher detalhes menores.

### 2. Regras de segurança e preservação

1. Comece com `git status --short`, `git diff --stat` e `git diff`. O worktree contém trabalho válido em andamento. Preserve tudo; nunca use `git reset --hard`, `git checkout --`, `git clean`, remoção recursiva ampla ou qualquer comando destrutivo.
2. Não altere `RemoteSttActivity.kt` nem as rotas REST/WebSocket.
3. Não altere `NativeDependencyManager.kt`, a versão/contrato do pacote nativo ou os ZIPs nativos.
4. Não imprima nem copie para logs as credenciais de `release/r2_config.json`. Só registre `bucket`, `public_base` e a presença dos campos privados.
5. Nunca versione pesos, ONNX, `.data`, `.bin`, WAVs de corpus, caches, credenciais ou logs gigantes no Git.
6. Scripts e documentação pequenos ficam no repositório; dados grandes ficam em `E:\SIG-granite-nar-lab\<experiment-id>`.
7. Não publique nada na raiz atual `models/granite/4.1-nar/` nem em `packages/`. Use somente:
   `models/granite/4.1-nar/experiments/<experiment-id>/`.
8. Não apague nem sobrescreva objeto R2 existente. Se a chave já existir com hash diferente, gere novo `experiment-id`.
9. Não faça upload de artefato que falhou em ONNX checker, ORT CPU ou paridade. Pode subir logs/manifest de falha, mas o papel deve ser `diagnostic`, nunca `candidate`.
10. Não marque contexto HTP como gerado. Sem telefone/compilador SM8850 validado, o máximo permitido é produzir um bundle `context-ready` com os QDQ e contratos.
11. Não use o emulador para afirmar suporte GPU/NPU. Ele não emula Adreno/HTP/FastRPC.
12. Use `apply_patch` para editar arquivos de texto do repositório. Downloads, exports, formatadores e geração mecânica de arquivos grandes podem usar as ferramentas próprias.

### 3. Persistência, baixo consumo de rede e retomada

A conexão é ruim. Todo passo precisa ser retomável.

- Crie um único `experiment-id` no formato `nar-qnn-YYYYMMDD-HHMMSS`.
- Crie `E:\SIG-granite-nar-lab\<experiment-id>` com:
  `source`, `cache`, `venv`, `exports`, `golden`, `calibration`, `quantized`, `packages`, `logs`, `reports` e `state`.
- Grave `state\run-state.json` após cada etapa, com status `pending/running/passed/failed/skipped`, timestamps, comando, exit code, arquivos e hashes.
- Se um arquivo existente tem tamanho e SHA esperados, não baixe/exporte novamente.
- Use downloads com retry, timeout alto, cache local e pouca concorrência (`max_workers=2`).
- Para Hugging Face, fixe `HF_HOME`, `HF_HUB_CACHE` e demais caches dentro do diretório do experimento; não redefina `$HOME` ou `$CODEX_HOME`.
- Para pip, use retries e cache no experimento.
- Para R2, use multipart de 64 MiB, concorrência 2, retry e retomada quando suportada.
- Nunca baixe de volta um arquivo multigigabyte apenas para validar se `HeadObject`, tamanho, metadado SHA-256 e checksum remoto suportado já passaram. Registre claramente o nível de verificação.
- Cada comando longo deve escrever stdout/stderr em `logs` e também emitir atualizações curtas ao usuário em intervalos razoáveis.

Se a sessão for interrompida, a próxima execução deve ler `run-state.json` e continuar, não reiniciar.

### 4. Fonte imutável

Use exatamente:

- modelo: `ibm-granite/granite-speech-4.1-2b-nar`;
- revisão: `a1e3416e25ce29ab3852778e54fa8b3bd59c4bf2`;
- licença declarada: Apache-2.0.

Baixe via `huggingface_hub.snapshot_download` com `revision` explícita e `local_dir` dentro do experimento. Inclua pesos, código remoto, configuração, tokenizer, vocab, preprocessor e o WAV oficial. Não use `main` sem revisão.

Depois do download:

- gere SHA-256 e tamanho de cada arquivo;
- grave `reports\source-manifest.json`;
- registre a revisão resolvida;
- rode um scanner simples para confirmar que não há links quebrados/arquivos LFS pointer no lugar do conteúdo;
- não suba os pesos-fonte ao R2; o Hugging Face permanece a origem, e o manifesto guarda a proveniência.

### 5. Ambiente Python

Crie um venv isolado no experimento. Comece com as versões de referência já documentadas:

- Python 3.11;
- PyTorch 2.9.1;
- torchaudio 2.9.1;
- Transformers compatível com a revisão IBM, no mínimo 5.5.3;
- ONNX/onnxscript atuais compatíveis;
- ONNX Runtime 1.29.0 para alinhar com o app;
- `huggingface_hub`, `safetensors`, `accelerate`, `numpy`, `soundfile`, `datasets`, `boto3`;
- dependências oficiais de quantização QNN disponíveis no pacote ORT 1.29.0.

Tente wheels CUDA compatíveis apenas se a GPU realmente couber. Não force o modelo inteiro na VRAM de 8 GB. CPU/low-memory loading é aceitável. Prefira confiabilidade a CUDA OOM.

Após resolver dependências:

- grave `reports\pip-freeze.txt`;
- grave versões de Python, torch, CUDA, transformers, onnx e ORT;
- faça um import smoke test;
- carregue config/tokenizer e depois o modelo com `trust_remote_code=True` a partir do snapshot local;
- registre pico de RAM aproximado e tempo;
- descarregue e faça GC entre exportações grandes.

Se Transformers estável não carregar a revisão, use o commit/tag mínimo compatível e registre o hash da dependência. Não use silenciosamente `main` mutável.

### 6. Ferramentas a criar no repositório

Crie `D:\Projetos\SIG\tools\granite\nar\` e implemente ferramentas pequenas e retomáveis, não um script monolítico:

- `download_source.py` — snapshot fixo e source manifest;
- `run_reference.py` — inferência PyTorch e tensors/texto dourados;
- `export_static.py` — encoder/projector/LLM por bucket;
- `inspect_onnx.py` — checker, shape report, ops, dynamic dims, external data;
- `validate_float.py` — PyTorch versus ORT por subgrafo e pipeline;
- `capture_calibration.py` — readers/inputs reais encadeados;
- `quantize_qnn.py` — preprocess + QDQ U16/U8;
- `validate_qdq.py` — QDQ CPU versus float;
- `build_experiment_manifest.py` — manifesto, hashes e contratos;
- `publish_experiment_r2.py` — multipart, metadados, manifest-last e verificação;
- `README.md` — comandos exatos e retomada.

Requisitos comuns:

- aceitar `--work-dir` absoluto;
- aceitar `--resume` ou ser idempotente por hash;
- nunca depender de cwd implícito;
- nunca conter credenciais ou caminhos secretos hardcoded;
- erro deve retornar exit code não zero e explicar artefato/etapa;
- escrever JSON estruturado além do texto humano;
- hash SHA-256 em streaming;
- arquivos temporários usam extensão `.partial` e rename atômico ao terminar;
- imports e funções puras relevantes devem ter testes unitários.

Não adapte os scripts Turbo existentes por substituição cega: eles são específicos do Granite 5.0.

### 7. Primeiro gate: validar o trabalho Android já presente

Antes do download multigigabyte, rode:

```powershell
& .\gradlew.bat :app:testDebugUnitTest
& .\gradlew.bat :app:lintDebug
& .\gradlew.bat :app:assembleDebug
```

O diff atual contém um harness debug ADB e protocolo JSON. Se houver erro introduzido por esse harness, faça a correção mínima, adicione/ajuste teste e repita os três gates. Não refatore o engine durante esse passo.

Valide também a sintaxe de:

`D:\Projetos\SIG\scripts\run-granite-nar-adb-benchmark.ps1`.

Registre os resultados em `reports\android-gates.json`. Se um gate falhar por problema anterior e não relacionado, registre evidência e prossiga com as tarefas de artefato independentes.

### 8. Inferência de referência

Rode o pipeline PyTorch oficial no WAV do repositório IBM e grave:

- texto bruto e normalizado;
- duração e sample rate;
- shapes/dtypes de features, hidden states selecionados, BPE logits, projector, sequência LLM e logits finais;
- `ctc_tokens`, `valid_audio`, slots e `S`;
- SHA-256 de cada tensor dourado salvo;
- revisão do modelo e ambiente.

Não persista logits gigantes para um corpus inteiro. Para o smoke oficial, pode salvar:

- tensores intermediários indispensáveis;
- argmax/top-k e pequenos slices determinísticos dos logits;
- hashes streaming do tensor integral, se viável.

O texto PyTorch é a referência. Se a execução oficial falhar, não exporte às cegas; corrija ambiente/código remoto ou marque bloqueio com stack completo.

### 9. Contratos exatos dos três wrappers

#### Encoder

- entrada `input_features`: float32 `[1,T,160]`;
- entrada/máscara somente se necessária ao wrapper, shape fixo `[1,T]`;
- `T` sempre estático e múltiplo de 200;
- saídas:
  - `encoder_bpe_logits`: float32 `[1,T/4,100352]`;
  - `multilayer_features`: float32 `[1,T,4096]`, concat das camadas `[4,8,12,-1]`;
- modo eval, dropout zero, sem cache/gradiente;
- pesos internos FP16 no candidato GPU; I/O float32.

#### Projector

- entrada `multilayer_features`: float32 `[1,T,4096]`;
- saída `audio_embeds`: float32 `[1,ceil(T/15)*3,2048]`;
- não embutir erroneamente a divisão por 12 se o contrato atual a faz no engine;
- validar a escala contra a fonte IBM.

#### LLM bidirecional

- entrada `inputs_embeds`: float32 `[1,S_bucket,2048]`;
- entrada `position_ids`: int64 `[1,S_bucket]`;
- entrada `attention_mask`: tipo aceito pelo export/QNN, shape fixo `[1,S_bucket]`;
- saída `logits`: float32 `[1,S_bucket,100352]`;
- `is_causal=False`, sem KV cache;
- padding somente no fim;
- máscara 1 no prefixo real e 0 no padding;
- os logits do prefixo real devem equivaler à execução de `S` exato.

Não altere `blank_token_id=100257`, `vocab_size=100352`, hidden 2048, `embedding_multiplier=12`, downsample 5, block 15 ou min edit length 8.

### 10. Ordem obrigatória de exportação

Não comece exportando todas as variantes. Use gates crescentes.

#### Etapa piloto float

1. encoder FP16 estático `T=200`;
2. projector FP16 estático `T=200`;
3. LLM FP16 estático `S=64` com máscara;
4. pipeline piloto completo;
5. paridade com PyTorch.

Use opset 17 inicialmente. Rode ONNX checker, shape inference e ORT CPU. Gere relatório de:

- inputs/outputs;
- cada dimensão;
- dtypes;
- operadores por tipo;
- dimensões simbólicas/dinâmicas restantes;
- external data e hashes;
- tamanho em disco;
- pico de memória/tempo de export.

Artefato candidato QNN não pode ter dimensão dinâmica.

#### Gate de máscara

Para pelo menos três valores reais de `S` menores que 64:

1. PyTorch com `S` exato;
2. PyTorch com padding até 64 + `attention_mask`;
3. ONNX `S=64` com o mesmo padding/máscara.

Compare logits apenas no prefixo real e o texto final. O texto deve ser idêntico; diferenças numéricas precisam caber na tolerância registrada. Se a máscara não preservar o prefixo, pare o LLM bucketizado, registre e não quantize esse LLM.

#### Batch float após o piloto passar

Exporte:

- encoder/projector `T = 200, 400, 800, 1200, 1600, 2000`;
- LLM `S = 64, 128, 256, 512, 768, 1024, 1408`.

Nomes determinísticos:

- `granite-4.1-nar-encoder-t0200-fp16.onnx`;
- `granite-4.1-nar-projector-t0200-fp16.onnx`;
- `granite-4.1-nar-llm-s0064-fp16.onnx`;
- external data com nome correspondente ou store compartilhado explicitamente manifestado.

Evite duplicar dezenas de GB:

- compare hashes de inicializadores entre variantes;
- reutilize external data quando offsets/contrato forem comprovadamente iguais;
- se deduplicação segura não for possível, mantenha as variantes locais mas **não** faça upload em massa ainda;
- registre uma tabela `reports\dedup-plan.json` com bytes únicos e duplicados;
- nunca altere referências external data manualmente sem carregar e rodar ORT depois.

Se `T=2000` ou `S=1408` exceder RAM, execute cada export em processo separado, descarregue o modelo e continue os buckets menores. Registre o bloqueio do bucket, não descarte os sucessos.

### 11. Corpus mecânico de calibração

Use áudio público e licenciado. Preferência inicial:

- dataset `google/fleurs`;
- subconjuntos `pt_br`, `en_us`, `es_419`, `fr_fr`, `de_de`;
- split público de validação;
- amostra determinística por seed;
- aproximadamente 20 arquivos por idioma no piloto, estratificados por duração;
- WAV oficial IBM como smoke adicional.

Não use áudio do usuário, da polícia, de produção ou qualquer arquivo pessoal encontrado na máquina.

Crie `calibration\corpus-manifest.jsonl` com dataset/revisão, licença, subset, split, id, idioma, duração, texto de referência, SHA-256 e bucket `T`. Registre a regra de normalização.

Se o download FLEURS for grande ou instável, conclua primeiro um corpus piloto menor (mínimo 5 por idioma), marque `pilot=true` e deixe o comando retomável para completar 20/idioma.

### 12. Captura encadeada para calibração

Não use números aleatórios.

- encoder: features reais do frontend exato;
- projector: `multilayer_features` produzidas pelo encoder float;
- LLM: `audio_embeds`, embeddings de slots reais, position ids e máscara reais.

Faça streaming e processe um bucket por vez. Não persista logits completos desnecessários. Grave:

- estatísticas min/max/percentis por input;
- NaN/Inf;
- contagem por idioma/duração/bucket;
- hashes dos shards;
- proveniência até o áudio de origem.

Antes de usar o frontend oficial Python como equivalente ao app, compare features com o contrato/binários atuais quando possível. Se a equivalência exata não puder ser provada sem telefone, registre a limitação, mas ainda pode criar o corpus piloto para experimentação local.

### 13. Quantização QNN piloto

Somente depois da paridade float piloto:

1. rode `qnn_preprocess_model`;
2. confirme shapes fixos;
3. use `get_qnn_qdq_config`;
4. ativações `QuantType.QUInt16`;
5. pesos `QuantType.QUInt8`;
6. formato QDQ;
7. calibração real encadeada;
8. valide QDQ com ORT CPU.

Quantize primeiro:

- encoder `T=200`;
- projector `T=200`;
- LLM `S=64` somente se o gate de máscara passou.

Gere:

- `*.preproc.onnx`;
- `*-qdq-u16u8.onnx` e external data;
- relatório de cobertura Q/DQ;
- nós/tensores excluídos;
- escalas, zero points ou hash do relatório;
- saturação e estatísticas;
- comparação float/QDQ.

Métricas mínimas:

- max abs/rel, NRMSE e cosseno dos intermediários;
- concordância top-1 CTC;
- sequência CTC;
- texto final;
- CER/WER quando houver referência;
- NaN/Inf.

O piloto só passa se o texto smoke for idêntico e as métricas não mostrarem regressão material. Se falhar, tente, nesta ordem, sem inventar mais:

1. revisar calibração e inputs;
2. manter I/O/cabeça de logits em precisão maior;
3. excluir LayerNorm/RMSNorm/Softmax/RoPE sensíveis conforme suporte oficial;
4. registrar como `needs-precision-review`.

Não tente U8/U8 antes de U16/U8 passar.

#### Batch QDQ

Se e somente se o piloto de cada família passar, processe os demais buckets. Um bucket falho não invalida os anteriores; registre status individual.

Sem telefone, esses modelos ficam `context-ready`, não `npu-approved`.

### 14. Validação local obrigatória por artefato

Para cada ONNX publicado como candidato:

- arquivo e external data existem;
- `onnx.checker` passa;
- shape inference passa ou limitação conhecida é registrada;
- não há dimensões dinâmicas;
- ORT 1.29 CPU cria sessão;
- ao menos uma inferência real passa;
- nomes/shapes/dtypes batem com o contrato;
- SHA-256 e tamanho em manifesto;
- paridade tem status `passed`;
- nenhuma credencial/caminho privado embutido.

Para arquivos maiores que a memória disponível, use APIs com external data e streaming; não use `read_bytes()` indiscriminadamente.

### 15. Empacotamento experimental

Crie em `packages\<experiment-id>`:

- `manifest.json` com schema 1;
- `source-manifest.json`;
- `environment.json`;
- `contracts.json`;
- `validation-summary.json`;
- `calibration-manifest.json` ou referência/hash;
- `artifacts/` com somente artefatos aprovados localmente;
- `diagnostics/` com relatórios pequenos;
- `README.txt` com limitações: sem validação Adreno/HTP/contexto.

Cada entrada de arquivo precisa de:

- caminho relativo;
- role (`float`, `qdq`, `support`, `diagnostic`, `manifest`);
- grafo;
- bucket;
- precisão;
- bytes;
- SHA-256;
- inputs/outputs;
- status de paridade;
- backend pretendido, nunca backend “aprovado” sem telefone.

O manifesto precisa registrar:

- revisão IBM exata;
- versões de torch/transformers/onnx/ORT;
- opset;
- hash do código exportador;
- hash do corpus/calibração;
- ORT Android esperado 1.29.0;
- QAIRT como `unknown/unverified` se a versão real não for descoberta com prova;
- target futuro `SM8850`, `HTP v81`;
- `phone_validation=false`;
- `qnn_context_present=false` salvo se houver compilador exato e prova válida.

### 16. Upload R2 autorizado

Implemente uploader genérico usando `boto3` e `release/r2_config.json`.

Regras:

1. confirme programaticamente `bucket == "sig-android"` e `public_base` esperado; caso contrário, pare o upload;
2. não imprima endpoint com credenciais, access key ou secret;
3. prefixo único:
   `models/granite/4.1-nar/experiments/<experiment-id>/`;
4. use multipart para arquivos grandes, chunks de 64 MiB e concorrência 2;
5. envie `Metadata.sha256`, role, experiment id e content type;
6. se a chave existe:
   - mesmo tamanho e SHA metadata: skip;
   - diferença: falhe; não sobrescreva;
7. suba dados/ONNX/relatórios primeiro;
8. suba `manifest.json` por último;
9. após cada upload, faça `HeadObject` e compare tamanho/metadados/checksum suportado;
10. teste HTTP HEAD público de cada objeto;
11. não delete objetos e não altere o pacote de produção atual;
12. grave `reports\r2-upload.jsonl` sem segredos.

Com conexão ruim, priorize upload nesta ordem:

1. manifests, scripts hash e relatórios;
2. piloto float `T=200/S=64` aprovado;
3. piloto QDQ U16/U8 aprovado;
4. variantes adicionais com bytes únicos;
5. duplicatas grandes somente se o plano de deduplicação justificar.

Não suba artefatos inválidos apenas para “completar”. Para falhas, suba somente relatórios pequenos em `diagnostics/`.

Ao final, grave a URL pública de `manifest.json`, mas não afirme que o app já a consome.

### 17. O que não fazer nesta execução

- não esperar ou simular ADB;
- não gerar benchmark falso de GPU/NPU no PC/emulador;
- não habilitar GPU/NPU do NAR na UI;
- não alterar o pacote NAR de produção;
- não substituir os arquivos atuais em `models/granite/4.1-nar/`;
- não gerar release APK;
- não publicar/alterar libs QAIRT ou nativas;
- não usar áudio privado;
- não inventar versão QAIRT;
- não afirmar que QDQ roda integralmente em HTP sem o teste estrito real;
- não criar contexto para um SoC genérico e rotulá-lo SM8850;
- não esconder fallback CPU;
- não continuar quantização em lote se o piloto correspondente falhou;
- não gastar rede rebaixando arquivos já validados por hash local/cache.

### 18. Gates finais do repositório

Depois de qualquer edição de código Android, rode novamente:

```powershell
& .\gradlew.bat :app:testDebugUnitTest
& .\gradlew.bat :app:lintDebug
& .\gradlew.bat :app:assembleDebug
```

Rode também testes dos novos scripts Python e `git diff --check`.

Não faça commit contendo arquivos grandes ou segredos. Pode fazer commits locais lógicos somente quando os testes relevantes passarem; não faça push. Antes de cada commit, liste explicitamente os paths staged e confirme que pertencem a este trabalho.

### 19. Handoff obrigatório para o agente “cérebro” amanhã

Crie:

`D:\Projetos\SIG\docs\handoff-granite-nar-artefatos-20260830.md`

O handoff deve ser pequeno, factual e conter:

- `experiment-id`;
- status geral `COMPLETE`, `PARTIAL` ou `BLOCKED`;
- revisão fonte e ambiente;
- diretório absoluto em `E:`;
- etapas passadas/falhas e primeiro erro de cada uma;
- lista de artefatos float/QDQ por bucket, tamanho e SHA;
- resultados de paridade;
- corpus/calibração realmente concluídos;
- bytes únicos/duplicados;
- URL pública do manifesto R2;
- objetos não enviados e motivo;
- gates Android/Python;
- git status e commits locais criados;
- perguntas que exigem decisão inteligente;
- cinco próximos comandos exatos quando o telefone voltar.

Também grave em `E:\SIG-granite-nar-lab\<experiment-id>\reports`:

- `final-summary.json`;
- `run-state.json` final;
- `artifact-index.json`;
- `validation-summary.json`;
- `r2-upload.jsonl`;
- `resume-command.txt`.

O `final-summary.json` deve usar status por fase e não declarar sucesso parcial como completo.

### 20. Condição de encerramento

Continue trabalhando sem pedir confirmação enquanto houver tarefa mecânica segura e independente. Encerre quando ocorrer uma destas condições:

1. todos os pilotos, batches viáveis, manifests e uploads experimentais passaram;
2. recursos locais tornam a próxima etapa impossível, mas todas as independentes terminaram;
3. falta credencial/software externo indispensável e isso foi provado;
4. um gate de qualidade impede corretamente a expansão, e os relatórios/handoff estão completos.

Sua resposta final deve começar exatamente com uma linha:

`MECHANICAL_RUN_STATUS=COMPLETE`

ou

`MECHANICAL_RUN_STATUS=PARTIAL`

ou

`MECHANICAL_RUN_STATUS=BLOCKED`

Depois informe, de forma concisa:

- experiment id;
- handoff path;
- diretório de artefatos;
- URL do manifesto R2, se publicado;
- maior sucesso;
- bloqueio principal;
- frase pronta para o usuário retornar ao agente principal: **“terminou lá, pode continuar”**.

Comece agora lendo os oito arquivos obrigatórios e auditando o worktree. Não pare após escrever um plano.

## FIM DO PROMPT
