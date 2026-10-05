# patches/ — pin de fontes do produto nativo

`vulkan-reset-v10.patch` — patch unificado (Vulkan/reset) aplicavel ao baseline
`e1bd73e31ba18bd36cdb65542a512fd8933901ca`. Aplicar com:

```bash
git apply --check vulkan-reset-v10.patch   # confere antes
git apply vulkan-reset-v10.patch           # aplica no worktree
```

`vulkan-reset-v10.manifest.txt` — SHA-256 por arquivo dos fontes usados no
build + contexto (toolchain, ABIs, fluxo). Permite reconstruir o produto se o
worktree `D:/svr11` for perdido.

Nota: o patch cobre APENAS a allowlist Vulkan/reset. O HEAD do repo tambem
contem WIP do executor (cache/instrumentacao OpenCL) que NAO entra no produto
(ver PROVENANCE-v10.md, secao matriz).
