sig - transcricao Granite

Como gerar o executavel:
1. Execute build_sig.bat.
2. O arquivo final ficara em dist\sig.exe.

Saidas:
- O aplicativo cria a pasta temp ao lado do executavel.
- As transcricoes sao salvas como .txt com o nome do arquivo original.
- O relatorio final fica em temp\transcricoes.html.

Configuracoes:
- Salvas em %APPDATA%\sig\settings.json.
- Padrao: 100.110.211.23:8100, 4 conversoes FFmpeg e 8 requisicoes.
