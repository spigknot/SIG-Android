# Modelo de relatório para o executor

Salvar como RELATORIO-FASE-XX-<data>-<tentativa>.md, acompanhado de report.json conforme [schema v2](SCHEMA-RELATORIO-FASE-v2.json). A forma estruturada indexa a evidência; não substitui o texto explicativo e os arquivos brutos.

## Identificação e conclusão proposta

- Fase, revisão do prompt, parecer que a abriu, executor e horários.
- Resultado proposto: concluída para revisão / parcial / bloqueada em recurso / falhou.
- Uma frase: o que funciona agora e em qual configuração; outra sobre o que ainda não foi demonstrado.
- Não escrever “aprovado pelo especialista” antes de existir parecer.

## Base e alterações

Tabela de HEAD/base/branch/patch SHA, arquivos alterados, mudanças preexistentes preservadas e estado final. Listar ferramentas, runtimes/modelos com revisões e hashes. Em aparelho: serial/SoC/ABI/Android/driver/UID/APK e nativos carregados. Não aplicável fica explícito.

## Cobertura da ordem

Uma linha por tarefa numerada do prompt: realizada, evidência identificada, teste/veredito e pendência. Entregar coverage.csv. Se houver divergência do escopo, explicar motivo e efeito antes de apresentar números.

## Método e configuração

Hipóteses, controles independentes, tolerâncias preregistradas, corpus e partições, ordem/seed/repetições, frio/quente e cache, política de cortes e medição. Enumerar os fatores que mudaram; se mais de um, não atribuir o ganho a somente um deles.

## Resultados funcionais e numéricos

Tabela por estágio/rota: backend solicitado/observado, shape/dtype, cobertura/fallback, erro numérico, IDs/texto e status. Evidências da mesma tentativa em ambos os lados da ponte. Descrever negativos/mutantes e qual regra os rejeitou. Incluir falhas, não apenas o melhor run.

## Qualidade

WER/CER/I/D/S, referência e normalização por SHA, amostras e duração, delta contra CPU, intervalos e erros dirigidos. Se não foi medida, escrever “não medida” e quais conclusões isso impede. Distinguir equivalência numérica de qualidade humana.

## Desempenho e recursos

Valores individuais, n válido/excluído com motivos, mediana/dispersão; tempos de carga/compile/frontend/estágios/ponte/total; RTF com definição; memória por evento e pico observado; thermal/bateria/energia com método. Referenciar arquivo bruto e fórmula de cada tabela. Não criar valores padrão para campos não medidos.

## Estabilidade, produto e limites

Erros/OOM/SSR/fallback, duração de soak, cancelamento, troca de perfil, cache e comportamento no UID normal. Listar limites de T/S/janela conhecidos. Não extrapolar aparelho, versão, corpus ou tempo de sessão.

## Verificação e reprodução

Comandos exatos, RC, resumo dos testes e seus logs, checagem de manifesto nos dois sentidos, patch pequeno revisável e dependências. Informar efeitos no dispositivo e onde ficaram artefatos; registrar restauração do estado acordado quando aplicável.

## Pendências e recomendação ao especialista

Separar defeito de implementação, hipótese refutada, ferramenta indisponível, falta de aparelho/credencial e trabalho fora de escopo. Para cada pendência, próxima ação mínima e recurso. Propor aceitar unidade / manter experimental / retrabalhar / encerrar método; não despachar a fase seguinte.

## Índice da entrega

Manifesto com SHA completo de cada artefato; locais de logs, métricas, patch e relatórios. Nenhuma transcrição privada ou chave no repositório. Tabelas do relatório devem poder ser regeneradas do bundle.
