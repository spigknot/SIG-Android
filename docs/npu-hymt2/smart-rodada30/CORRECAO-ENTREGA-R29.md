CORRECAO-ENTREGA-R29 — proveniencia do adapter (achado da auditoria!)
=====================================================================
Declaracao do descompasso (SEM fraude — causa MECANICA identificada!):
- r29_settensor_gen.inc (entregue!) TEM as guardas full-only (2x KQSET-RECUSA!);
- r29_settensor_adap.inc (entregue na R29!) NAO tinha (passava zero fixo!) —
  ele foi capturado da RAIZ /root/ antes do r29_t4.py regenerar os adapters
  dentro de /root/r29_t/<cenario>; a raiz nunca foi atualizada (o t4.py so'
  escreve nos cenarios!). O especialista reproduziu: com o adap entregue =
  2 RED; regenerando do gen = 0 RED. Causa mecanica, nao comportamental.

CORRECAO (esta pasta!):
- entrega-real-r29/<cenario>/r29_settensor_adap.inc + r29_cores_gen.h =
  os arquivos REALMENTE COMPILADOS por cenario (copiados de
  /root/r29_t/<cenario>/ — NAO da raiz!).
- Coerencia conferida por hash de comportamento: green=2 guardas;
  m1=0 (cases removidos); m2=1 (so Q6); m3=2; m4=2; m5=0 (guards removidos!)
  — cada mutante com o efeito esperado na matriz.
- r29_extrai.py e r29_guard2.py (citados e agora ANEXADOS — os mesmos
  usados no servidor!) + r29_t4.py/r29_muts3.py (ja' na pasta R29!).

LACUNAS DECLARADAS (sem retrofabricar!):
- r28_fixture_inv.c (R28) nao preservado (registrado na R29!);
- metadado "L0..L0" no cabecalho do r29_settensor_gen.inc era cosmetico —
  os intervalos REAIS estao no stdout do extrator (L1318..L1391!) e nos
  hashes por corpo (ff728239...!) — corrigido NO CABECALHO a partir da R30
  (r30_* usam o formato correto: L%d..%d reais!).
- A raiz /root/r29_settensor_adap.inc permanece como estava (nao sobrescrita
  silenciosamente!); a cadeia nova desta rodada usa caminhos proprios.

PROXIMA CADEIA: um script unico (r30_pipeline.sh!) reproduz
extracao->adaptacao->mutacao->build->run->export em diretorio isolado e
FALHA se o adapter exportado nao casar com o gen (guarda de proveniencia!).
