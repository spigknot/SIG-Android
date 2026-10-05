# Testes nativos versionados (fontes, nao binarios)

Fontes de teste do harness nativo — versionadas NOMINALMENTE para
reproducibilidade em CI/clone limpo. Binarios (.exe/.pdb/.o/.a/.so),
logs e fixtures grandes permanecem fora do Git (ver .gitignore raiz).

## Escopo (allowlist nominal)

Runners de reset/Q8/loader/safe_deploy/boundary/sampler/probe + headers
+ scripts + fixtures minimas deterministas (.glsl.orig, REFERENCIA.txt).

EXCLUIDOS deliberadamente:
- layer_diff.cpp / layer_diff_v2.cpp — instrumentacao de diagnostico,
  fora do produto (nao versionar).
- llama_harness.cpp — harness completo (build/CI), nao um teste isolado;
  permanece fora do Git.
- *.exe / *.pdb / *.o / *.a / *.so — binarios (ignorados globalmente).
- fixtures grandes (.bin/.csv/.npy) — ignoradas; o DownloadPlanTablesTest
  usa fixtures ZIP em app/src/test/resources/native-deps/ (tambem
  ignoradas — ver nota abaixo).

## Nota sobre fixtures ZIP (DownloadPlanTablesTest)

O DownloadPlanTablesTest (arquivo-a-arquivo) depende de
app/src/test/resources/native-deps/*.zip (48-55 MB por ABI). Essas
fixtures NAO sao versionadas (artefatos de pacote). Opcao futura:
fixture minimal determinista que valide o contrato sem baixar o ZIP
de 50 MB em unit CI (numerical runtime probe != mock fixture).

## Reproduzir

Os runners compilam com o NDK toolchain (llvm-clang) e rodam no
host (linux/windows) ou no dispositivo via safe_deploy.sh. Ver os
comentarios de cada *.cpp para build/run.
