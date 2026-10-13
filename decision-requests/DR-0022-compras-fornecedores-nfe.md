# DR-0022 — Compras e Fornecedores: importação de NF-e, custo de aquisição e recebimento

## Status

`PROPOSTA — AGUARDANDO DECISÃO DO OWNER`

## Contexto

O `DR-0021` (decidido em 2026-10-13) liberou a `TASK-0021`. O `AG-05` §163 manda abrir Decision Request para rateio de
frete, rateio de despesas de aquisição, divergência aceitável, consolidação de documentos, crédito e substituição de
item; o briefing do owner fixou algumas regras (abaixo, "Já decidido") mas não estas. Cada decisão traz opções, uma
recomendação e **qual incremento ela bloqueia** — nenhuma regra abaixo é implementada como definitiva antes da
decisão. Onde a recomendação for usada como padrão, será uma **configuração parametrizável marcada "não validada"**,
nunca uma regra embutida no código.

### Já decidido pelo owner (briefing de 2026-10-13) — não reabrir

- Pedido de compra **não** movimenta estoque; somente recebimento físico confirmado movimenta.
- Importar XML não significa receber mercadoria nem pagar; XML com cobrança/pagamento **não** marca conta como paga.
- A mesma NF-e não pode ser importada duas vezes como compra efetiva; idempotência, auditoria e transação obrigatórias.
- NF-e de R$ 1.943,10 em 3 × R$ 647,70 gera **uma** compra com **três** obrigações, sem duplicar o valor econômico.
- Não inventar classificação tributária; pontos dependentes de regime/legislação/contador ficam parametrizáveis.
- Preço de venda ≠ custo de aquisição. Autenticação continua por sessão. Sem alterações destrutivas no Supabase.
- Aprovação de compra por permissão no backend, sem limiar financeiro no MVP (`AG-05` §44).

## Decisões pendentes

### D1 — Composição do custo de aquisição do item (alimenta o custo médio do Estoque) — bloqueia I4

- **A (recomendada):** custo do item = `vProd − desconto do item + IPI do item + ICMS-ST do item + rateio de
  frete/seguro/outras despesas (D2)`. ICMS normal, PIS e COFINS **não** entram. É o tratamento usual de quem não
  recupera esses créditos (Simples Nacional, citado no `AG-06` §41). Cada componente é um parâmetro
  (`INCLUDE_IPI`, `INCLUDE_ICMS_ST`, `INCLUDE_ICMS_NORMAL`) marcado "não validado pelo contador".
- B: custo = apenas valor líquido do produto (sem tributos nem frete).
- C: custo informado manualmente em cada recebimento.

### D2 — Rateio de frete, seguro e outras despesas entre os itens — bloqueia I4

- **A (recomendada):** proporcional ao valor líquido do item; centavo residual no item de maior valor.
- B: proporcional à quantidade. C: rateio manual.
- Frete cobrado por transportadora à parte (CT-e) **não** altera o valor da NF-e nem suas parcelas: vira custo adicional
  da compra com obrigação própria (fora do I3).

### D3 — Divergência de recebimento (quantidade, preço, item) — bloqueia I4

- **A (recomendada):** toda divergência exige aceite explícito, com justificativa e permissão
  `PURCHASE_ACCEPT_DIVERGENCE`; sem tolerância automática. O pedido original é preservado e a diferença registrada
  (`AG-05` §§61-67). Recebimento acima do pedido só com aceite.
- B: tolerância percentual configurável abaixo da qual a divergência é aceita sem justificativa.

### D4 — Quando nascem as obrigações financeiras da compra por NF-e — bloqueia I3

- **A (recomendada):** na confirmação da compra importada, uma conta a pagar por duplicata, ligadas à compra. A NF-e
  estabelece a dívida; recebimento e pagamento são independentes (`AG-05` §114).
- B: somente no recebimento físico.

### D5 — Reimportação e anulação do documento — bloqueia I3

- **A (recomendada):** chave de acesso única entre documentos **não anulados**. Anular o documento só é possível com a
  compra cancelada e sem recebimento ativo; a linha anulada permanece no histórico e a chave pode ser importada de
  novo. Mesma chave com hash de XML diferente é bloqueada e alertada, nunca sobrescreve.
- B: chave única para sempre (nota cancelada nunca reimporta).

### D6 — Correspondência de itens do XML com produtos — bloqueia I3

- **A (recomendada):** ordem de sugestão: (1) vínculo já confirmado (fornecedor + código do fornecedor); (2) GTIN igual
  ao do produto; (3) candidatos por descrição apenas como lista. **Nada é aplicado sem confirmação humana na primeira
  vez**; confirmar grava o vínculo. Exige `gtin` e `ncm` opcionais no catálogo (migration do módulo `productcatalog`).
- B: sem GTIN no catálogo; vínculos só por código do fornecedor.

### D7 — Unidades de medida da NF-e × catálogo — bloqueia I3

O catálogo aceita `UNIDADE, LITRO, METRO, QUILOGRAMA, GALAO_5L, BALDE_20L`.

- **A (recomendada):** equivalências diretas fixas (UN/UND/PC/PÇ→UNIDADE; L/LT→LITRO; M/MT→METRO; KG→QUILOGRAMA).
  Qualquer outra (CX, PAR, JG, KIT…) exige **fator de conversão** informado pelo usuário e gravado no vínculo
  (ex.: 1 CX = 12 UNIDADE). Sem conversão a linha não confirma. Quantidade e custo unitário são convertidos e a
  quantidade/unidade originais ficam no documento.
- B: ampliar o domínio de unidades do catálogo.

### D8 — Segregação de funções na aprovação — bloqueia I5

- **A (recomendada):** quem criou o pedido não o aprova, exceto perfil `DONO`.
- B: sem restrição (qualquer detentor de `PURCHASE_APPROVE`).

### D9 — CNPJ da própria oficina — bloqueia I3

- **A (recomendada):** configuração `OWN_TAX_ID` do módulo Compras, alterável por `DONO`, validada como CNPJ. Sem ela a
  importação alerta que não confirmou o destinatário. É a base para detectar nota destinada a outro CNPJ e para
  distinguir pessoas jurídicas relacionadas (raiz de CNPJ diferente = PJ distinta).
- B: derivar do primeiro destinatário importado (rejeitada: dado de entrada não pode definir a identidade).

### D10 — Fornecedor relacionado — bloqueia I2

- **A (recomendada):** atributo `related_party` + observação no fornecedor, sem tratamento contábil diferente; relatórios
  podem segregar. Eliminação intercompany **fora** desta fase.

### D11 — Nota sem duplicatas (à vista)

- **A (recomendada):** o usuário informa vencimento/parcelas na confirmação; sem duplicata não se presume pagamento
  nem vencimento — bloqueia I3 só para notas sem cobrança.

### D12 — Verificação oficial da NF-e

- Registrado como limitação: nesta Task a autorização é a **declarada no XML** (alerta permanente "consulta oficial
  não realizada"). Consulta à SEFAZ/Portal Nacional exige certificado A1 (`DR-0021`, `AG-08`) e fica para Task própria.
  Verificação criptográfica local da assinatura XMLDSig pode entrar como incremento posterior (I1b).

## Impacto

```text
Funcional: define custo, rateio, divergência, momento das obrigações e unidades.
Financeiro: D4/D11 definem quando nasce a obrigação; D1/D2 definem o valor que entra no custo médio.
Estoque: D1/D2/D7 alteram o custo unitário informado ao módulo de Estoque.
Segurança: D3/D8 usam permissões novas (ver docs/architecture/compras/TASK-0021-arquitetura-compras.md).
```

## Responsável e decisão

```text
Responsável pela decisão final: Proprietário do produto
Decisão final: PENDENTE
```

## Histórico

- 2026-10-13 — DR criada junto com a `TASK-0021`; recomendações A para todas as decisões.
