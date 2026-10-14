# Revisão dos downloads — 14/10/2026

Correção solicitada: conservar as qualidades já baixadas do Granite e verificar a
correspondência entre arquivo atual, lista, progresso e conclusão em todas as rotas.

| Rota | Resultado da revisão |
|---|---|
| Granite 4.1 NAR | A limpeza após o download apagava as outras variantes do editor. Agora só remove os arquivos do pacote legado. As três qualidades atuais e seus arquivos parciais permanecem salvos. |
| Granite 4.1 NAR — plano | Incluídos os tamanhos dos dois arquivos de 8 bits que faltavam no fallback local. A consulta ao manifesto e a leitura dos hashes saíram da thread da interface. |
| Granite 4.1 NAR e 5.0 Turbo — progresso | Removidas conclusões estimadas por bytes e marcações ao iniciar um arquivo. Eventos distinguem transferência, validação e arquivo salvo. O nome do Turbo perdeu o prefixo `lib/`, que não existia na lista. Atualizações de transferência são limitadas a uma a cada 300 ms. |
| Whisper e VAD Silero | Os modelos já eram mantidos por opção. O menu agora distingue arquivo completo de vazio/truncado. Download valida tamanho antes de ativar; o VAD usa o mesmo diálogo e preserva a alternativa de URL. |
| Texto / Hy-MT2 | Os modelos já eram mantidos por opção. Acrescentadas verificação de tamanho, preservação do destino anterior e marcação de conclusão somente depois de salvar. |
| Componentes nativos | Instalação da mesma versão reutiliza componentes presentes. A UI mostra as etapas de validação e instalação do ZIP; sucesso deixa o resultado disponível até “Continuar”. O contrato do ZIP, versão, URLs e hashes não mudaram. |
| QAIRT | Reutiliza a instalação presente; transferência, validação e instalação são exibidas separadamente. Não marca o ZIP como concluído antes do sucesso. |
| Atualização do APK | Mantém o APK anterior em caso de falha. Recusa arquivo vazio ou com tamanho diferente do asset anunciado pela release. A conclusão só ocorre após salvar, antes de abrir o instalador do Android. |
| Prompts | Conferido o download dos quatro arquivos permitidos: compara antes de substituir padrões, valida o conjunto e preserva textos personalizados e rascunhos. Sem alteração necessária. |
| Limpeza de cache | Conferido que varre `cacheDir`/`externalCacheDir`; os modelos e componentes instalados ficam fora dessas pastas. Sem alteração necessária. |

O diálogo compartilhado identifica o arquivo em andamento com `↓`, reserva `✓`
para arquivos salvos, distingue percentual por arquivo do total e atualiza o
resumo dos arquivos restantes, inclusive ao concluir. Um total desconhecido não
é exibido como `-1%`.

## Verificação

- `GraniteNarEngineTest`: arquivos pequenos com manifesto e hashes reais de
  fixture; alterna máxima → 8 bits → 4 bits → máxima, aplica a limpeza e comprova
  que todos permanecem completos, com zero bytes pendentes. Também cobre pacote
  incompleto e arquivo adulterado.
- `DownloadProgressSequenceTest` e `DownloadSizeFormatTest`: receber 100% não
  conclui arquivo, validação não conclui, arquivo grande não adianta arquivos
  pequenos, snapshots da UI não mudam retroativamente e nomes/estados coincidem.
- `DownloadFileStorageTest`: arquivo vazio/truncado não é instalado; falha
  preserva o destino; ativação não toca nas outras opções.
- `AppUpdateCheckerTest`: servidor HTTP local; sucesso, retry, corpo vazio e
  tamanho diferente do asset, preservando o APK anterior nas falhas.
- `DownloadPlanDialogTest`: dois testes instrumentados no emulador Pixel_9;
  eventos controlados, estados visíveis, botão “Continuar”, fechamento e capturas
  da tela. Não usa rede nem baixa modelos.
- Suíte contratual do harness com `-Quiet`, testes unitários completos,
  `lintDebug`, `assembleDebug` e `assembleDebugAndroidTest`.

Resultado final: gate central aprovado; 710 testes unitários registrados, com
708 aprovados e 2 pulados em `GeminiSttRealApiTest`; zero falhas ou erros. Os dois
testes instrumentados da interface passaram no APK final. Capturas revisadas
visualmente confirmam o arquivo correto, o total identificado e “faltam 0 B”
na conclusão. `lintDebug` e os builds foram aprovados.

Evidência local dos gates: `build/download-fix-validation.json`. Capturas da tela:
`build/download-ui-check/download-ui-tests/`. O aparelho físico não foi alterado.
Não houve publicação de APK, modelos ou pacotes nativos. Os testes não repetem
downloads reais de vários gigabytes nem executam inferência de áudio.
