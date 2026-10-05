# Continuação NAR — auditoria do relatório de 14–15/09 e fechamento de perfis

Objetivo do usuário: perfis úteis em PT-BR com opções de qualidade, velocidade e memória, utilizando CPU/NPU/GPU quando o ganho for real. Continue os prompts de perfis e dos quinze áudios, mas este documento prevalece nas correções abaixo. Não publicar APK, promover produção ou fazer push. Implementações de diagnóstico, correção, pilotos e publicação experimental de modelos continuam autorizadas. Voz/transcrições pessoais ficam locais.

## 1. Parecer do cérebro: progresso real, conclusão ainda parcial

O holdout e os reparos P1/P2 são avanços. O relatório não encerra a tarefa: pessoais 1–14 ainda não foram avaliados; 15 foi segmentado, mas não transcrito integralmente; replay, EPContext, GPU, Q4 e CTC permanecem pendentes. Os candidatos a perfis ainda não foram comparados de forma suficiente.

Não declarar N1 aprovado por delta de MEDIANAS zero. O gate global pede taxas agregadas: soma dos erros dividida pela soma de palavras/caracteres de referência nos MESMOS itens. Mediana por áudio é indicador secundário e pode esconder uma minoria de erros grandes. Não criar um novo limite obrigatório por áudio a partir do limite global; os casos individuais são revisão dirigida, não um gate numérico adicional inventado.

Auditoria do cérebro sobre metrics-holdout-ptbr.jsonl, usando exatamente o normalizador atual, nos 24 pares EQ/N1 com captura válida:

| Perfil | Erros/palavras | WER micro | Erros/caracteres | CER micro |
|---|---|---|---|---|
| EQ | 40 / 257 | 15,5642% | 84 / 1240 | 6,7742% |
| N1 | 35 / 257 | 13,6187% | 83 / 1240 | 6,6935% |

Delta N1−EQ: −1,9455 pp WER e −0,0806 pp CER. É resultado DESCRITIVO favorável à NPU nesse estrato curto, não prova de superioridade geral. Pequena amostra, referências de dataset, normalização com limitações, uma tentativa N1 falha e lacuna de identidade do APK precisam acompanhar os números. Contra REF, três itens não bastam. Não misturar os 74 EQ com os 24 N1 para decidir backend.

## 2. Corrigir primeiro o avaliador — achados reproduzidos no código

Arquivo auditado: D:\SIG-granite-nar-lab-rebuild\nar-next-20260914-0950\quality\qualidade.py.

1. PALAVRAS_NEGACAO contém nao, mas normaliza preserva acentos. Assim, “não autorizou”→“autorizou” não é detectado. A afirmação zero negação perdida está sem suporte.
2. A regra de repetição de palavra ignora duplicação quando o token já existe uma vez na referência: referência “casa”, hipótese “casa casa” retorna sem repetição artificial. Detectar excesso por alinhamento/ocorrência, preservando “eu vi, eu vi” realmente falado.
3. A regra de “cola de duas palavras” usa substrings em qualquer ponto da referência. Isso é heurística, não prova de repetição. Casos como politagem precisam de alinhamento/revisão, e não rótulo automático grave. Separar suspected_repetition de confirmed_repetition.
4. A equivalência numérica ordena a lista de tokens. Ela considera equivalentes “quinze caixas e duas bolsas” e “duas caixas e quinze bolsas”, embora os valores tenham sido trocados entre objetos. Além disso, substituição palavra a palavra não interpreta números compostos. Preservar ordem e contexto; onde não houver parser robusto, usar unknown/needs-review, não true.
5. A placa é montada com palavras da frase inteira e comparada por substring, inclusive em áudios sem placa. Isso produz resultados sem sentido. Ativar avaliação de placa somente nas fixtures marcadas, extrair o trecho relevante e comparar a sequência completa, inclusive tamanho. Não aceitar prefixo curto como placa correta.
6. normaliza troca TODA pontuação por espaço, contrariando o contrato anterior de preservar separadores significativos em datas/decimais. Versionar a correção, preservar os resultados antigos e recalcular a partir das saídas brutas. Não usar dois normalizadores diferentes sem identificar a versão.
7. avalia trata saída vazia capturada como qualidade unknown. Diferenciar dado AUSENTE de string vazia comprovadamente produzida. Em fala com referência não vazia, saída vazia válida significa todas as palavras excluídas: WER/CER=1 pelo contrato de edição. Captura ausente continua unknown. Silêncio tem avaliação própria, sem divisão por zero.

Criar testes de regressão ANTES da correção: não/autorizou; duas negações em posições trocadas; casa/casa casa; repetição falada; aprender/aprenderender; números com entidades trocadas; 1,5/15; horário por extenso versus dígitos; placa BCD1F23 versus BCD1F2; frase comum sem placa; saída ausente, vazia e silêncio.

Não prometer entendimento semântico geral com regex. CER/WER lexical permanece principal; equivalência editorial/números é coluna adicional auditável. Guardar teste de “23:35” versus “vinte e três e trinta e cinco”; não editar referências depois de ver o candidato para melhorar o gate. Referência publicada FLEURS pode servir a benchmark com origem dataset_reference; não depende de escuta humana prévia de 75 arquivos. Casos suspeitos e referências pessoais precisam de status separado.

## 3. Agregação e catálogo: implementar analisa_metricas.py

Implementar a ferramenta faltante, ou equivalente explicitamente indicado, com testes pequenos cujos resultados sejam calculáveis manualmente. Entradas: saídas brutas, referências versionadas, ledger e identidade da configuração. Saída: métricas micro, macro, medianas, distribuição, denominadores e pareamento por áudio/hash. Congelar a regra de seleção de repetições; não escolher a melhor hipótese de cada run.

Publicar taxas de execução junto das taxas de qualidade condicionais. Uma falha de captura/sessão nunca desaparece com retry posterior. Recalcular relatórios sem refazer inferências quando inputs/saídas/identidade forem recuperáveis; classificar resultados antigos como legacy-build-attribution quando não forem.

Os limites de shortlist PT-BR permanecem: Equilibrado +1 pp CER/WER; Rápido/leve +2 pp CER e +3 pp WER; versus REF e com comparativo EQ conforme os prompts. Benefício: >=20% end-to-end sobre EQ OU >=30% de RAM/download total para perfil Leve. Nem mediana zero nem um erro individual acima desse valor substituem o gate global. Casos graves continuam revisão separada.

## 4. Identidade do APK, operação e armazenamento

Não afirmar que o APK de outro agente só difere pela sonda sem auditar o binário/código. Ausência da sonda comprova diferença, não seu alcance. Preservar dados, mas atribuição incompleta impede aceitação do APK final por esses testes.

Registrar SHA-256 dos APKs instalados (base e splits, se existirem), assinatura/metadados, ORT/QAIRT, artefatos e opções. Fingerprint versionName/versionCode/lastUpdateTime não substitui hash. Tirar snapshot antes/depois de cada rodada; abortar ou invalidar a rodada se mudar. Lock de um script não impede outro agente de instalar APK: combinar janela exclusiva de aparelho com o usuário quando houver concorrência real. Não matar processos/emuladores alheios.

Revalidar no APK final identificado uma triagem curta dos resultados principais e o gate operacional antes de promoção. A bateria 39/40 permanece registrada com falha externa explicada; não transforma automaticamente EQ em 20/20 no APK final. As falhas posteriores de external data e OOM mantêm estabilidade pendente.

O erro de arquivo é prioridade: arquivo inexistente num instante não está resolvido porque apareceu depois. Testar pacote experimental IMUTÁVEL, sem restauração/renomeação/download enquanto houver criação/uso de sessões. Confirmar PID encerrado e lock de operações antes de trocar arquivos. Instrumentar errno/caminhos/mode/path versus bytes e estado imediatamente antes/depois. Sem retry silencioso.

Expandir o portão de compatibilidade de P2 para TODAS as sessões antes de criá-las, inclusive LLM. Se uma combinação LLM-NPU conhecida como não suportada pode derrubar DSP no load antes de politicaBucket, a proteção é incompleta. Não depender exclusivamente de llm_backend_cpu=true em extra de teste para proteger a seleção normal. Resolver perfil/backend por estágio e rejeitar combinação não aprovada ANTES de inicializar o runtime. LLM CPU em perfil híbrido é decisão explícita, não fallback oculto.

## 5. OOM no áudio de 37,14 s: localizar alocação antes de reduzir limite

Uma falha não estabelece teto universal de 37 s. Capturar stack completa e tamanho/shape/dtype de inputs/outputs na linha de alocação. Verificar cópias JNI→Java, FloatBuffer retornado pelo ORT, conversões via value/arrays, materialização de logits, fechamento em finally e sessões antigas retidas. O código evitar FloatArray explícito não prova ausência de cópia dentro da API.

Primeiro reproduzir CPU em processo novo com o mesmo áudio/hash, sem outras sessões abertas. Registrar PSS/heap por estágio. Corrigir o ponto demonstrado: reutilizar buffers, consumir tensores vivos quando suportado, reduzir saídas com ArgMax se só IDs forem necessários e houver paridade, ou segmentar controladamente. Não aumentar heap arbitrariamente nem mascarar erro com truncamento.

Testar a correção no reprodutor, um áudio menor e execução repetida; depois nos maiores buckets CPU. Se só segmentação resolver, documentar como decisão do pipeline e validar cobertura e texto integral. NPU t2000 continua proibida nesta rodada.

## 6. Prioridade de produto: terminar testes que distinguem perfis

Depois dos reparos de avaliação/estabilidade, executar nesta ordem:

1. **Pessoais:** D:\15 audios. Rodar pelo menos EQ nos 1–14 e N1 elegíveis t400, mantendo referência expected_script/unverified quando não houver escuta. Isso permite métricas provisórias e lista objetiva de dúvidas, não deve impedir qualquer execução. Para dúvidas de transcrição, entregar trechos/tempos a confirmar. Não afirmar que preparou inventário como se tivesse testado transcrição.
2. **Pessoal 15:** executar de fato os segmentos e recompor. Plano t400 comum CPU/NPU para comparação isolada; plano maior CPU como pipeline separado. Cobertura em amostras precisa ser exata; preferir corte PCM por índices se ffmpeg produzir ±1 amostra. Cortes em fala são limitação registrada. Medir WER/CER integral e latência incluindo todas as partes; conferir final. Os WAVs órfãos podem permanecer ignorados pelo manifesto, não gastar rodada com limpeza.
3. **CTC rascunho:** implementar rota que carrega somente encoder/tokenizer necessários, sem projector/LLM/embeddings do editor. Validar decode contra fonte. Esse piloto testa uma redução real do trabalho, diferente de só quantizar pesos. Usar mesmos áudios EQ, qualidade absoluta e ganho total. Se incoerente, rejeitar com evidência; não corrigir texto por regex.
4. **Q4 Leve:** testar artefato existente, mesmos encoder/projector de EQ, medir pacote inteiro/RAM e qualidade em PT-BR. Não rejeitar por 2/2 de uma fala sem medir taxa. Sem reabrir int2b ou novas varreduras de LLM.
5. **N2/contexto:** implementar EPContext mode=2 t400, opções de sessão separadas de provider; binários/wrappers por grafo, três reaberturas em processo novo sem recompilação. Qualidade e funcionalidade do cache são distintas. Preparação longa pode ser custo único; restauração/primeira inferência/quente são métricas diferentes.
6. **GPU:** piloto encoder/projector float t200 isolados, controle CPU idêntico, partição comprovada, depois t400 somente se passar. Registrar nó/fase dos erros. LLM permanece CPU inicialmente; não testar LLM-NPU ou atualizar runtime só para contornar o resultado. Não concluir GPU inviável sem executar o piloto.
7. **REF e corpus:** ampliar REF para os mesmos itens dos candidatos sobreviventes. REF em só três áudios não aprova delta contra REF no holdout. Fazer avaliação CPU/REF também nos áudios longos após o reparo OOM. Não aprovar int8b versus float apenas por paridade CPU/NPU do mesmo int8b.

P5 replay continua útil para a repetição lexical e pode ser feito em uma amostra dirigida; não deve consumir toda a rodada impedindo os perfis novos. Capturas grandes e profiling ficam separados de benchmark temporal.

## 7. Entrega esperada nesta próxima rodada

Entregar pacote de evidências, não apenas outro relatório de pendências:
- avaliador corrigido, testes que demonstram cada bug antigo e agregados recalculados;
- falha de external data e OOM corrigidas OU reprodutor/stack/hipótese discriminada com impedimento preciso;
- guardas de compatibilidade incluindo LLM e prova no APK final identificado;
- transcrições pessoais 1–14 e integral 15, com status das referências;
- pilotos CTC, Q4, contexto e GPU executados, ou erro técnico concreto de tentativa registrada;
- catálogo de poucos perfis Pareto com qualidade PT-BR, memória/download e carga fria/quente, limites por bucket e backend real.

Não há obrigação de preencher cinco nomes se só dois perfis forem úteis. CPU U8/int8b é candidato Equilibrado; N1 é aceleração por estágio ainda sem vantagem end-to-end demonstrada; Q4 pode ser Leve; CTC pode ser Rascunho; N2/GPU precisam das medições. Fiel é nome provisório até REF mostrar benefício de qualidade. Não transformar porcentagens de x86 em promessas Android.

Implementar instrumentos faltantes dentro do escopo autorizado. Continuar tarefas independentes quando faltar aparelho; não instalar no dispositivo de outra tarefa. Rodar gates Android apropriados e entregar commits/patchs próprios sem bypass/push, com restauração de modelos/preferências verificada. Evidências pessoais permanecem privadas/localmente; modelos experimentais e relatórios sanitizados podem ser publicados em prefixo novo com hashes.
