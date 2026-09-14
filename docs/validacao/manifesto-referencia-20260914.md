# Manifesto de referência — padronização FFmpeg (F0)

Gerado em 14/09/2026 15:16. Corpus em `%LOCALAPPDATA%\Temp\bateria*` (fora do repo);
usar como referência antes/depois de cada fase. Nenhum destes arquivos é reescrito pelas fases.

| Artefato | SHA-256 completo | Bytes |
|---|---|---:|
| C1 fonte | `ae6af91bfa492f0d518dca3faec6683203190dff00ed9949c5baf338848b5db6` | 258537 |
| C1b bipes | (ausente) | — |
| C1c marcadores | `ad5f4196f70a9d1d3b2505c2adc00115d425fe9b3296bbcdf8a32a9271ede54f` | 202385 |
| C2 sem audio | `d37b13bec5012ad3c8c24825dcba9b841eb14f62828ec163c678b01d9c407423` | 182243 |
| C3 duas faixas | `46af02df92cce4ad9ac4934186703a0ae765629a8ff83ed9a37b9549b390cd67` | 333596 |
| C3b perfis distintos | `0c6636bd66dcd9a116a306b0d1749a1c4609d5d40d8e452a5054df41645e12ea` | 333457 |
| C4c linha mod8 | `e64507fed5b6fbb8d9701dde9d8cd872f6637a2a7e4f6f3faa856ef8f033a960` | 11140 |
| C4e linha mod9 | `b06c9cd12fd9ae1c9b1320f6dc44c03fdaf06f8104d0b39f5f8d1257e2e1f7aa` | 10207 |
| C4f colunas | `9ca8f305d97f805ed74de28da8d4eeaf15068f1ae9446c6f8a9338bf89025605` | 40884 |
| C5 principal 10s | `58455af7271444f338ff71fd49f8332b0ee6d83ee9529e4fd080821390267bf3` | 1920078 |
| C5 inserido 2s | `1756240d4831c5f5b1be0f54b9318c23378306f865b47112e67d30b41645426a` | 384078 |
| Saida Windows preciso (ref) | `b48058e5c4c82b8b85687a774412a76d17130e97a9a2605ea9ec7ab0f4d59451` | 75018 |
| Saida Windows copia (ref) | `039ff48db3c87dcc1fec5376b9af0c3f3757d9c5d3b810f7115155c76ccf74e9` | 159737 |
| Saida Windows smartcut (ref) | `78e8c8040f64858ecbe667c92d2e302bf31f8e666d331e5317460b4f33313913` | 124665 |
| Saida Android smartcut (ref) | `72ea486fd656b1bd16097902d330a4c4b04ef622bbd9fa5e2d91a4e52aef6fca` | 154640 |
| Saida Android precisa AAC (variante) | `4ef90e23b7a01a77bdc90360dbc7e3aa36784ea17e2c014b6aa054fe373158cd` | 140203 |
| Saida Android 2 faixas (defeito) | `3ed09bb04103f05728dee31bc89f5f6ee6eb37f8447fc0a27ac28aa4118eef5f` | 257804 |
| Saida Android crop (defeito) | `5dbac348a165eb0f6a60a0a5000bf83157feeead3d9304bd23b2d0ae2f912dfc` | 4246 |
| Saida celular smartcut C1c | `6d87e3c214c3798c34408d293945c8ea853ada24fd0fa2189a3f76ab52d29c54` | 119064 |
| Saida celular inserir 12s | `30efb9281de703c9056f0155ccb30d652f597aea38e2bd060477b7dbed99ba8f` | 2304078 |

## Binários e estados

- ffmpeg Windows: `ffmpeg version 8.0.1-full_build-www.gyan.dev Copyright (c) 2000-2025 t`
- Android: ffmpeg-kit 6.1.1 (pacote nativo do app).
- HEAD Android: `12bbabc96cac5b644d83e35c3af845b5d8798c5b`
- HEAD Windows: `e60918d510c419240dec87995a048daf025987cd` (com 0 alterações locais não commitadas do usuário)
- APK referência: `app-debug.apk`, sha256 `f90c61b3284091319ce2be5a24a1664bfd3db2e37e2087cbca0afddcc54347b9`
