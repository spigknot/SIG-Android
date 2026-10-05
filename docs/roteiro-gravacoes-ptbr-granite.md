# Gravações pessoais para testar o Granite — PT-BR

Grave se for conveniente: o PC auxiliar pode continuar com áudios públicos brasileiros enquanto isso. Estas gravações ajudam a medir o comportamento com sua voz; não representam todas as vozes brasileiras.

## Como gravar

- Grave uma fala por arquivo, com voz e velocidade naturais, num ambiente habitual. Não precisa falar de forma exageradamente articulada.
- Deixe aproximadamente um segundo de silêncio antes e depois. Pode enviar M4A, WAV ou o formato normal do gravador; o agente converte preservando os originais. Evite encaminhar por um modo que aplique transcrição ou alteração de voz.
- Se possível, nomeie 01, 02, etc. Se não for prático, apenas mantenha a ordem. Não leia o número do item nem diga “abre aspas”.
- Os nomes e fatos abaixo são fictícios. Não acrescente dados de pessoas, ocorrências ou documentos reais.
- Não precisa refazer uma fala por um pequeno engano: avise o que mudou, ou deixe que o agente transcreva literalmente o áudio antes de avaliar o modelo.
- Comece pelos itens 01–08. Os demais são opcionais. Não acelere para caber num limite de segundos; o agente medirá os frames e selecionará os buckets.

## Falas curtas — prioridade

01. Uma opção para o ano sabático é viajar e aprender.

02. Eu quero aprender, compreender e depois empreender.

03. O motorista não confirmou a entrega do pacote.

04. O motorista confirmou a entrega do pacote.

05. João e Jéssica encontraram Luís em São José do Rio Preto.

06. A reunião será no dia quatorze de setembro, às quinze horas e trinta minutos.

07. O valor informado foi de mil duzentos e trinta e quatro reais e cinquenta centavos.

08. Foram quinze unidades, não cinquenta, e duas caixas ficaram no depósito.

## Falas adicionais — opcionais

09. A placa fictícia é bê, cê, dê, um, éfe, dois, três.

10. O endereço fictício é Rua das Acácias, número cento e vinte e sete, bloco bê, apartamento quarenta e dois.

11. Eu vi, eu vi o carro parar. Quer dizer, ouvi o barulho e só depois olhei pela janela.

12. O pacote pesava um vírgula cinco quilo. A distância era de quinze quilômetros, e o trajeto levou cinquenta minutos.

13. Na segunda-feira, João chegou à oficina por volta das oito e meia. Ele disse que o veículo não apresentava falhas no dia anterior. Depois da avaliação, pediu que nenhuma peça fosse substituída sem autorização.

14. Durante a conversa, a testemunha afirmou que não viu quem abriu a porta. Ela ouviu dois barulhos, esperou alguns segundos e chamou a vizinha. Mais tarde, esclareceu que o carro era cinza, não preto, e que não conseguiu ler a placa.

Opcional: uma gravação de trinta a sessenta segundos contando, com suas palavras, como foi um passeio, uma receita ou outra situação sem dados sensíveis. Não precisa escrever o texto antes. Ela testa fala espontânea; a referência será transcrita e conferida antes da avaliação.

## Instruções ao PC auxiliar

1. Aplicar docs/adendo-granite-ptbr-prioritario.md. Guardar originais e hashes em diretório local privado do laboratório saudável. Não publicar voz, tensores derivados ou transcrições pessoais no R2; publicar somente resultados agregados sanitizados. Os modelos experimentais continuam publicáveis conforme autorização anterior.
2. Converter uma cópia para WAV PCM mono 16 kHz, documentando ferramenta/versão e hashes. Não aplicar denoise, normalização de volume ou remoção de silêncio na primeira avaliação. Não sobrescrever originais.
3. Ouvir/transcrever o que foi efetivamente dito. O roteiro é uma expectativa, não ground truth automático. Conferir a referência antes de olhar as hipóteses dos candidatos. Se a escuta não estiver disponível, marcar referência pendente de confirmação; não usar um candidato como árbitro de si mesmo.
4. Manter esses áudios fora de todos os pools de calibração/quantização. Após usá-los para escolher/corrigir variantes, passam a ser regressões pessoais conhecidas; não chamá-los de holdout novo nas rodadas seguintes.
5. Comparar EQ, N1 e os demais perfis sobreviventes no MESMO áudio convertido, com hashes e opções congelados. Primeiro medir qualidade; depois velocidade em rodada separada. Calcular frames reais e nunca cortar uma palavra para encaixar t400.
6. Itens longos podem ultrapassar t400: rodar em CPU nos buckets disponíveis. Nesta fase a NPU continua limitada a t200/t400; registrar unsupported-bucket, sem chamar isso de falha de qualidade. Não generalizar desempenho NPU curto para os itens longos.
7. Itens 03/04: conferir preservação da negação. Itens 01/02: registrar repetições de morfema, inclusive aprenderender. Item 11: preservar a repetição e a autocorreção realmente faladas; não classificá-las automaticamente como artefato.
8. Itens 06–10/12: reportar WER/CER lexical e checagem de conteúdo separada. “Quinze” e “15” podem representar o mesmo valor, mas isso não autoriza apagar diferenças lexicais no indicador principal. Registrar equivalência de formato e erros reais de valor, horário, endereço ou placa. Para 09, o conteúdo esperado, se a leitura foi fiel, é BCD1F23; soletração e sequência alfanumérica são formatos distintos do mesmo conteúdo. Nenhuma correção automática da saída para forçar equivalência.
9. Entregar uma tabela por item: referência literal, saída por perfil, WER/CER, erro de conteúdo, repetição artificial, bucket, backend efetivo por estágio, tempo quente/frio quando medido e status operacional. Manter texto pessoal nessa tabela apenas localmente.
10. Não agregar as falas pessoais ao corpus público escondendo sua origem: mostrar três conjuntos separados — público PT-BR, pessoal dirigido e pessoal espontâneo — com contagens de falantes/áudios. Não contar várias gravações da mesma pessoa como várias vozes independentes.
