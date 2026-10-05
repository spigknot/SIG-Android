# Gravações do usuário — mapa 1.wav a 15.wav

O usuário informou ter gravado as quatorze falas do roteiro e uma fala adicional de aproximadamente 59 segundos. Pode nomear os arquivos 1.wav a 15.wav conforme a tabela abaixo. Os áudios ainda precisam ser recebidos/localizados e inspecionados; não declarar validação ou duração exata a partir deste documento.

Renomear extensão não converte formato: se o original for M4A, manter 1.m4a etc. ou converter uma cópia efetivamente para WAV, preservando o original. O número é a identidade do item; a extensão deve refletir o formato.

## Textos esperados, informados pelo usuário/roteiro

| Arquivo | Texto esperado |
|---|---|
| 1.wav | Uma opção para o ano sabático é viajar e aprender. |
| 2.wav | Eu quero aprender, compreender e depois empreender. |
| 3.wav | O motorista não confirmou a entrega do pacote. |
| 4.wav | O motorista confirmou a entrega do pacote. |
| 5.wav | João e Jéssica encontraram Luís em São José do Rio Preto. |
| 6.wav | A reunião será no dia quatorze de setembro, às quinze horas e trinta minutos. |
| 7.wav | O valor informado foi de mil duzentos e trinta e quatro reais e cinquenta centavos. |
| 8.wav | Foram quinze unidades, não cinquenta, e duas caixas ficaram no depósito. |
| 9.wav | A placa fictícia é bê, cê, dê, um, éfe, dois, três. |
| 10.wav | O endereço fictício é Rua das Acácias, número cento e vinte e sete, bloco bê, apartamento quarenta e dois. |
| 11.wav | Eu vi, eu vi o carro parar. Quer dizer, ouvi o barulho e só depois olhei pela janela. |
| 12.wav | O pacote pesava um vírgula cinco quilo. A distância era de quinze quilômetros, e o trajeto levou cinquenta minutos. |
| 13.wav | Na segunda-feira, João chegou à oficina por volta das oito e meia. Ele disse que o veículo não apresentava falhas no dia anterior. Depois da avaliação, pediu que nenhuma peça fosse substituída sem autorização. |
| 14.wav | Durante a conversa, a testemunha afirmou que não viu quem abriu a porta. Ela ouviu dois barulhos, esperou alguns segundos e chamou a vizinha. Mais tarde, esclareceu que o carro era cinza, não preto, e que não conseguiu ler a placa. |

### 15.wav — texto adicional fornecido pelo usuário

Quando os ponteiros do relógio começam a girar mais rápido do que a sua capacidade de processar o ar que entra nos pulmões, cada fração de segundo deixa de ser uma medida abstrata de tempo e passa a ser uma barreira física que você precisa romper a qualquer custo. Você passa os olhos por cada frase sem hesitar, tropeçando nas sílabas mentais enquanto a mente tenta manter o ritmo acelerado das palavras, ignorando vírgulas, atropelando pausas e torcendo para que a compreensão não se perca no meio do turbilhão de informações que passam diante da sua visão como postes vistos da janela de um trem em alta velocidade. Não dá tempo de parar para contemplar metáforas, muito menos para respirar com calma entre uma oração e outra; o objetivo aqui é unicamente vencer o cronômetro, engolindo termos, sintetizando ideias em microssegundos e empurrando a narrativa para frente

## Instruções obrigatórias ao PC auxiliar

1. Ler também adendo-granite-ptbr-prioritario.md e roteiro-gravacoes-ptbr-granite.md. Localizar os arquivos na pasta indicada pelo usuário; não presumir que já estejam no laboratório. Registrar hash, container/codec reais, canais, sample rate, duração e correspondência numérica, ordenando por número e não lexicograficamente (1, 2, ... 15).
2. Guardar texto acima como expected_script. Produzir verified_reference somente após conferir a fala efetiva: omissões, trocas, hesitações e repetições reais devem ser preservadas. Não “corrigir” a voz para coincidir com o roteiro e não usar a saída do candidato como ground truth. Se não for possível ouvir/conferir, marcar reference_status=unverified e métricas provisórias.
3. Preservar os originais. Converter cópias para PCM mono 16 kHz, sem denoise, aceleração ou corte de conteúdo. Registrar hashes de ambos e comando de conversão. Preservar a referência literal e calcular versão normalizada pelo normalizador definido nos prompts.
4. Testar as quinze gravações nos perfis compatíveis. Cada áudio tem duração/frames reais; não assumir que 1–14 caibam em t400. Mesmos inputs e referências em cada comparação. Nomes, números e negações têm análise de conteúdo adicional; pontuação editorial é separada.
5. Estes são quinze áudios de UM falante, não quinze falantes nem holdout público. Manter conjunto pessoal separado de FLEURS. Não usar para calibração. Não publicar voz, transcrição pessoal ou tensores derivados no R2.

## Tratamento específico de 15.wav: fala longa

A duração informada é 59 s. Medir duração/frames reais e consultar o limite atual do engine; não enviar ao single-shot se exceder seu contrato. Nunca truncar silenciosamente para t2000, t400 ou qualquer outro limite. O teste precisa cobrir começo, meio e fim, incluindo “empurrando a narrativa para frente”.

Se o app já tiver segmentação de áudio longo, usar a implementação existente e registrar política de corte, fronteiras, overlap, buckets e recomposição. Se não houver, registrar single_shot=unsupported e criar um ensaio de segmentação apenas no harness experimental, com cortes em pausas quando possível, limites calculados pelo frontend real e cobertura integral. Não apresentar esse ensaio como funcionalidade já integrada ao app.

Executar primeiro CPU nos segmentos suportados. Para NPU, manter política t200/t400: não liberar buckets grandes para acomodar 59 s. Um ensaio NPU segmentado é permitido, com cada segmento conferido antes da sessão; se não houver corte seguro dentro do limite, registrar o tratamento escolhido e o risco de cortar palavra. Não alterar a velocidade do áudio para fazê-lo caber.

Para comparar backends, usar EXATAMENTE a mesma lista de segmentos e mesma recomposição em CPU/NPU/GPU. Se comparar CPU com segmentos maiores e NPU com menores, nomear comparação de pipelines, não efeito isolado do backend. Guardar offsets de amostras, cobertura e eventuais sobreposições. Não remover duplicatas no texto com regra livre: qualquer recomposição de overlap deve ser determinística, auditável e avaliada contra referência integral, preservando repetições genuínas.

Medir WER/CER do texto recomposto INTEIRO, além de erros por trecho, perda/duplicação nas fronteiras e integridade da cauda. Tempo total inclui frontend, cortes, todas as inferências, trocas de sessão e recomposição. Reportar carga fria separada e fator de tempo real = tempo total / duração real. Não chamar uma transcrição parcial correta de aprovação do áudio de 59 s.

Não ampliar a pesquisa de segmentação indefinidamente: uma política experimental documentada e um controle CPU com a mesma política bastam nesta rodada. Entregar limitações e diferenças contra a referência; uma política de produto para fala longa pode ser decidida depois.
