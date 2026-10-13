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

## Análise técnica por decisão (D1–D11) — 2026-10-13

Elaborada por `AG-11` com `AG-10` e `AG-06`, a pedido do proprietário. **Não decide nada**: a
`Decisão final` continua `PENDENTE`. Cada bloco traz o problema de negócio, a alternativa A da DR, as
demais alternativas, as consequências em estoque, custo médio, compras e financeiro, os riscos
operacionais e a recomendação técnica.

Base factual usada (código já em produção nesta branch, não suposição):

- Custo médio: `inventory/domain/Stock.add` — média ponderada `(saldo × médio + qtd × custo) ÷ novo saldo`,
  **4 casas, HALF_UP**; entrada **sem** custo mantém o médio; custo nulo significa *desconhecido*, nunca zero.
- Rentabilidade: `inventory/InventoryCostQuery.partsCostForWorkOrders` usa o custo **gravado na movimentação**
  no instante da baixa. Um custo de aquisição errado não fica no Estoque: ele chega ao resultado da OS.
- Financeiro: `finance.payable` tem `supplier` como **texto livre**, sem FK para fornecedor e **sem vínculo com
  compra**; tem `idempotency_key UNIQUE`, `due_date` obrigatório, `amount > 0`, e cancelamento preserva histórico.
- Dinheiro: `DR-0007` — 4 casas internas, **2 casas no que é cobrado**, `HALF_UP`, e total = soma de parcelas
  já arredondadas.

---

### D1 — Composição do custo de aquisição do item

**Problema de negócio.** Quando a peça entra pela NF-e, qual valor vira custo dela no estoque? A nota traz
valor do produto, desconto, IPI, ICMS, ICMS-ST, PIS, COFINS e frete. Incluir tributo que a oficina
**recupera** infla o custo e esconde margem; excluir tributo que ela **paga de fato** mostra lucro que não
existe.

**Alternativa A (recomendada pela DR).** Custo = `vProd − desconto do item + IPI + ICMS-ST + rateio de
frete/seguro/outras despesas (D2)`. ICMS normal, PIS e COFINS ficam fora. Cada componente é parâmetro
(`INCLUDE_IPI`, `INCLUDE_ICMS_ST`, `INCLUDE_ICMS_NORMAL`) marcado "não validado pelo contador".

**Alternativas.** B — custo = apenas valor líquido do produto. C — custo digitado manualmente a cada
recebimento.

**Consequências.**
- *Estoque*: define o `unitCost` de cada entrada; é o único número que o Estoque recebe de Compras.
- *Custo médio*: entra direto na média ponderada. Erro aqui contamina todo o saldo futuro do produto, não só a
  nota — e a média não é recalculável para trás sem reprocessar movimentações.
- *Compras*: A exige ler e somar campos tributários por item do XML; B não exige quase nada; C transfere o
  trabalho para o operador em cada recebimento.
- *Financeiro*: A aproxima o custo do desembolso real, melhorando rentabilidade por OS. B subestima o custo e
  **superestima o lucro**. C torna o resultado dependente da disciplina de quem digita.

**Riscos operacionais.** A está correta para quem **não** recupera crédito (Simples Nacional). Se a oficina
mudar de regime, os parâmetros têm de mudar junto, e o custo histórico já gravado **não** muda — o que é certo
contabilmente, mas gera uma quebra visível na série do custo médio. B é o risco mais sério: decisão de preço
tomada sobre custo subestimado. C praticamente garante divergência entre operadores.

**Recomendação técnica.** **A**, com três condições: (1) os três parâmetros nascem com o valor de A e ficam
marcados "não validado pelo contador" na interface; (2) a composição do custo de cada item é **gravada junto
com a entrada** (valor base, cada tributo somado, rateio aplicado), para que a conta seja auditável anos depois
sem reabrir o XML; (3) mudança de parâmetro **não** recalcula o passado. Sem a condição (2) a decisão é
irrastreável, e é ela que separa "custo médio confiável" de "número que ninguém sabe explicar".

---

### D2 — Rateio de frete, seguro e outras despesas

**Problema de negócio.** O frete vem como um valor único da nota, não por item. Sem ratear, ele fica fora do
custo e a peça parece mais barata do que custou. A nota 21 é um caso simples; notas com itens de valores muito
diferentes não são.

**Alternativa A (recomendada pela DR).** Proporcional ao valor líquido do item; centavo residual no item de
maior valor.

**Alternativas.** B — proporcional à quantidade. C — rateio manual. Frete por CT-e cobrado pela transportadora
**não** altera o valor da NF-e nem suas parcelas: vira custo adicional da compra com obrigação própria.

**Consequências.**
- *Estoque*: altera o `unitCost` de **todos** os itens da nota, não de um.
- *Custo médio*: o rateio é somado antes da ponderação; com A, item caro absorve mais frete.
- *Compras*: A e B são automáticas; C exige tela de rateio e trava a confirmação até alguém distribuir.
- *Financeiro*: não altera o total a pagar (o frete já está na nota) — altera apenas **onde** o custo aparece.
  É neutro no caixa e relevante na margem por peça.

**Riscos operacionais.** A distorce quando a nota mistura item caro e leve com item barato e pesado (o frete
real segue peso, não valor). B distorce no caso oposto. O centavo residual precisa de regra única, senão a soma
dos custos ratea­dos não fecha com o frete da nota — e aí o estoque valorizado não bate com a nota.

**Recomendação técnica.** **A**, com o residual no item de maior valor **e** uma asserção de fechamento: a soma
dos rateios tem de ser exatamente o valor rateado, verificada em teste. Alinha com a `DR-0007` (soma de
parcelas arredondadas). Deixar o método parametrizável (`FREIGHT_ALLOCATION=VALUE|QUANTITY`) custa pouco agora
e evita migration de dados depois, caso a oficina conclua que peso explica melhor o frete dela.

---

### D3 — Divergência de recebimento

**Problema de negócio.** Chegou quantidade diferente do pedido, preço diferente, ou item que não foi pedido. O
sistema aceita calado, recusa, ou exige que alguém assuma a diferença?

**Alternativa A (recomendada pela DR).** Toda divergência exige aceite explícito, com justificativa e permissão
`PURCHASE_ACCEPT_DIVERGENCE`; sem tolerância automática. O pedido original é preservado e a diferença
registrada. Recebimento acima do pedido só com aceite.

**Alternativas.** B — tolerância percentual configurável abaixo da qual a divergência passa sem justificativa.

**Consequências.**
- *Estoque*: define se entra a quantidade **pedida** ou a **recebida**. Só a recebida é verdade física; qualquer
  tolerância que entre pela quantidade pedida cria saldo que não existe na prateleira.
- *Custo médio*: preço divergente aceito entra na média. Com B, uma diferença "dentro da tolerância" entra sem
  ninguém ver.
- *Compras*: A exige tela de aceite e uma permissão nova; B é mais rápida no balcão.
- *Financeiro*: a conta a pagar segue a **nota**, não o recebido. Divergência física com nota inalterada é
  justamente o caso que precisa ficar registrado para a cobrança ser contestada depois.

**Riscos operacionais.** A gera atrito no recebimento: sem alguém com a permissão, a mercadoria não entra. B
gera o risco oposto, e pior: tolerância percentual aplicada sobre valor pequeno é centavo, sobre valor grande é
dinheiro — a mesma regra tem efeitos muito diferentes.

**Recomendação técnica.** **A**. Quantidade que entra no estoque é **sempre a recebida**; a divergência é
registro, não ajuste silencioso. Recomendo ainda que a permissão `PURCHASE_ACCEPT_DIVERGENCE` seja concedida a
`DONO` e `GERENTE_ADMINISTRATIVO` (mesmo par usado em `QUOTE_PRESENT`) e **não** ao perfil operacional, e que a
justificativa seja texto obrigatório — não lista de motivos, que viraria "outros" em uma semana.

---

### D4 — Quando nascem as obrigações financeiras da compra por NF-e

**Problema de negócio.** A NF-e 21 tem 3 duplicatas de R$ 647,70. A dívida existe a partir de quando: da
importação da nota, ou do recebimento físico da mercadoria?

**Alternativa A (recomendada pela DR).** Na confirmação da compra importada: **uma conta a pagar por
duplicata**, ligadas à compra. A NF-e estabelece a dívida; recebimento e pagamento são independentes.

**Alternativas.** B — obrigações somente no recebimento físico.

**Consequências.**
- *Estoque*: nenhuma. A é justamente o que separa dívida de mercadoria.
- *Custo médio*: nenhuma.
- *Compras*: A precisa de um contrato público de Compras → Financeiro para criar as contas; hoje esse contrato
  **não existe**.
- *Financeiro*: **aqui está o ponto estrutural.** `finance.payable` tem `supplier` como texto livre, sem FK, e
  **sem coluna que aponte para a compra**. Implementar A exige migration nova (vínculo `purchase_id` e/ou
  `supplier_id`), senão as três contas da nota 21 ficam órfãs e ninguém consegue provar que pertencem à mesma
  nota. O `idempotency_key UNIQUE` já existente é o mecanismo correto para a nota 21 não gerar seis contas em
  vez de três — exatamente o objetivo que você declarou.

**Riscos operacionais.** Com A, nota importada e mercadoria que nunca chega deixa dívida registrada; precisa de
caminho de cancelamento (ligado a `D5`). Com B, a oficina perde o vencimento de uma nota que ainda não foi
recebida — risco financeiro bem maior do que o de A.

**Recomendação técnica.** **A**, com três exigências: (1) migration nova ligando a conta a pagar à compra e ao
fornecedor cadastrado — sem isso A fica rastreável só por texto; (2) a chave de idempotência derivada de
`chave de acesso da NF-e + número da duplicata`, de modo que reimportar não duplique; (3) o valor econômico
conferido na gravação: soma das duplicatas = total da nota, recusando a confirmação quando não fechar. É a
proteção direta contra "duplicar contas a pagar".

---

### D5 — Reimportação e anulação do documento

**Problema de negócio.** A mesma nota pode chegar duas vezes (e-mail do fornecedor, reenvio, erro do
operador). E uma nota cancelada pelo fornecedor pode ser reemitida com a mesma chave? O sistema precisa
impedir compra em dobro sem impedir a correção legítima.

**Alternativa A (recomendada pela DR).** Chave de acesso única entre documentos **não anulados**. Anular só com
a compra cancelada e sem recebimento ativo; a linha anulada permanece no histórico e a chave volta a poder ser
importada. Mesma chave com hash de XML diferente é bloqueada e alertada, nunca sobrescreve.

**Alternativas.** B — chave única para sempre (nota cancelada nunca reimporta).

**Consequências.**
- *Estoque*: A exige que anular seja impossível se houver recebimento ativo; sem isso, anula-se a nota e o saldo
  físico fica sem origem.
- *Custo médio*: entrada já feita e depois anulada deixaria o médio contaminado — por isso a trava de "sem
  recebimento ativo" é parte da decisão, não detalhe.
- *Compras*: A é um índice único **parcial** (`WHERE anulado IS NULL`), igual ao padrão já usado no catálogo
  (`uq_product_internal_code`) e em fornecedores (`uq_supplier_document`).
- *Financeiro*: anular tem de cancelar as contas a pagar da compra, preservando histórico — comportamento que
  `Payable` já suporta (cancelamento não apaga).

**Riscos operacionais.** B é simples e cria um beco sem saída: nota cancelada e reemitida com a mesma chave
nunca entra, e o operador recorre a lançamento manual — perdendo justamente a rastreabilidade que a importação
existe para dar. A é mais permissiva e depende das travas acima estarem corretas.

**Recomendação técnica.** **A**, com o hash do XML gravado sempre e a verificação de divergência como **alerta
bloqueante**, não aviso. Mesma chave + hash diferente é o caso suspeito por excelência (nota alterada) e não
pode ser resolvido por sobrescrita em nenhuma hipótese.

---

### D6 — Correspondência de itens do XML com produtos do catálogo

**Problema de negócio.** O fornecedor chama de `OL ATF DX III 1L` o que o catálogo chama de `Óleo ATF Dexron
III`. Vincular errado joga custo e saldo no produto errado — e ninguém percebe até o inventário.

**Alternativa A (recomendada pela DR).** Ordem de sugestão: (1) vínculo já confirmado (fornecedor + código do
fornecedor); (2) GTIN igual; (3) candidatos por descrição apenas como lista. **Nada é aplicado sem confirmação
humana na primeira vez**; confirmar grava o vínculo. Exige `gtin` e `ncm` opcionais no catálogo.

**Alternativas.** B — sem GTIN no catálogo; vínculos só por código do fornecedor.

**Consequências.**
- *Estoque*: vínculo errado = entrada no produto errado. É o erro mais difícil de desfazer, porque já afetou
  dois custos médios (o que recebeu e o que deixou de receber).
- *Custo médio*: idem.
- *Compras*: A exige migration no `productcatalog` (`gtin`, `ncm`) — módulo **diferente** do de Compras, o que
  torna essa a decisão com maior alcance arquitetural entre as onze.
- *Financeiro*: nenhum efeito direto; o total da nota não muda com o vínculo.

**Riscos operacionais.** A primeira importação de cada fornecedor é lenta (confirmação item a item); da segunda
em diante é quase automática. Sem a confirmação humana (nenhuma alternativa propõe isso, mas é a tentação
óbvia), um match por descrição erra silenciosamente. GTIN no Brasil vem em branco ou inválido com frequência —
por isso ele é critério 2, e não 1.

**Recomendação técnica.** **A**. Acrescento: o vínculo deve ser gravado por **fornecedor + código do
fornecedor**, nunca por descrição, e deve ser visível e removível numa tela — vínculo errado confirmado uma vez
se repetiria para sempre em silêncio. O `gtin` no catálogo entra validado (dígito verificador já existe em
`BrazilianTaxIdAndGtinTest`), senão vira campo de lixo.

---

### D7 — Unidades de medida da NF-e × catálogo

**Problema de negócio.** A nota diz `CX` e o catálogo só conhece `UNIDADE, LITRO, METRO, QUILOGRAMA, GALAO_5L,
BALDE_20L`. Uma caixa de 12 unidades lançada como 1 unidade cria saldo de 1 onde há 12, e custo unitário 12
vezes maior.

**Alternativa A (recomendada pela DR).** Equivalências diretas fixas (UN/UND/PC/PÇ→UNIDADE; L/LT→LITRO;
M/MT→METRO; KG→QUILOGRAMA). Qualquer outra (CX, PAR, JG, KIT…) exige **fator de conversão** informado e gravado
no vínculo (1 CX = 12 UNIDADE). Sem conversão, a linha não confirma. Quantidade e custo são convertidos; a
quantidade/unidade originais ficam no documento.

**Alternativas.** B — ampliar o domínio de unidades do catálogo.

**Consequências.**
- *Estoque*: define a quantidade que entra. É o erro de maior impacto imediato entre todos os onze.
- *Custo médio*: o custo unitário é dividido pelo fator. Fator errado erra o médio proporcionalmente.
- *Compras*: A exige campo de fator no vínculo de item e trava de confirmação.
- *Financeiro*: nenhum efeito — o total da nota não muda.

**Riscos operacionais.** A trava "sem conversão não confirma" é o que impede o erro, e é também o que vai
irritar no primeiro uso. B parece mais simples e é pior: `CX` como unidade de estoque significa que a oficina
não sabe quantas unidades tem, e `GALAO_5L`/`BALDE_20L` já existentes mostram que esse caminho multiplica
unidades sem resolver a conversão.

**Recomendação técnica.** **A**, com a quantidade e a unidade **originais da nota** preservadas no documento
(já previsto) e o fator gravado no vínculo do item, não na linha da nota — assim a segunda nota do mesmo
fornecedor já vem convertida. Validar `fator > 0` com as 4 casas da `DR-0007`.

---

### D8 — Segregação de funções na aprovação

**Problema de negócio.** Quem cria o pedido de compra pode aprová-lo?

**Alternativa A (recomendada pela DR).** Quem criou não aprova, exceto o perfil `DONO`.

**Alternativas.** B — sem restrição para qualquer detentor de `PURCHASE_APPROVE`.

**Consequências.** Nenhuma em estoque, custo médio ou financeiro: é controle interno. Em compras, A exige
gravar o autor do pedido (já é o padrão do projeto: `created_by` em todas as tabelas) e comparar na aprovação.

**Riscos operacionais.** A oficina é pequena. Com A e sem o `DONO` disponível, um pedido criado pelo único
gerente presente não é aprovável — e o trabalho para em um controle que existe para fraude de escala maior. A
exceção do `DONO` resolve isso só quando o dono está por perto.

**Recomendação técnica.** **A**, pela razão de controle, mas registro que esta é a decisão de **menor** risco
técnico e **maior** risco de atrito operacional das onze. Se o proprietário preferir B no piloto, a
consequência é contornável e reversível (é só uma verificação na aprovação), diferente de D1, D4 e D7. Bloqueia
apenas o I5, o último incremento.

---

### D9 — CNPJ da própria oficina

**Problema de negócio.** Sem saber o próprio CNPJ, o sistema não consegue afirmar que a nota é destinada a
ela — e importaria nota de terceiro como compra própria.

**Alternativa A (recomendada pela DR).** Configuração `OWN_TAX_ID` do módulo Compras, alterável por `DONO`,
validada como CNPJ. Sem ela, a importação alerta que não confirmou o destinatário.

**Alternativas.** B — derivar do primeiro destinatário importado (a própria DR já rejeita: dado de entrada não
pode definir identidade).

**Consequências.** *Estoque/custo médio*: indiretas — nota de terceiro importada vira saldo e custo que não são
da oficina. *Compras*: uma configuração e uma verificação na prévia. *Financeiro*: evita conta a pagar de
dívida alheia.

**Riscos operacionais.** Baixos. O único cuidado é não **bloquear** a importação quando `OWN_TAX_ID` está
vazio, e sim alertar, para não travar o primeiro uso.

**Recomendação técnica.** **A**, sem ressalva. B é inaceitável pelo motivo que a própria DR dá. Acrescento usar
a **raiz** do CNPJ (8 primeiros dígitos) para reconhecer filial do mesmo grupo, e tratar raiz diferente como
pessoa jurídica distinta — é o que liga esta decisão à D10.

---

### D10 — Fornecedor relacionado

**Problema de negócio.** Compras de empresa do mesmo dono precisam ser identificáveis, sem inventar tratamento
contábil que o contador não aprovou.

**Alternativa A (recomendada pela DR).** Atributo `related_party` + observação no fornecedor, sem tratamento
contábil diferente; relatórios podem segregar. Eliminação intercompany fora desta fase.

**Alternativas.** A DR não registra alternativa; a implícita é não marcar nada.

**Consequências.** Nenhuma em estoque, custo médio ou financeiro — é marcação informativa. Em compras, é uma
coluna e um campo de tela.

**Riscos operacionais.** Praticamente nulos. O risco seria o oposto: alguém supor que a marcação **faz** algo
contábil.

**Recomendação técnica.** **A**. É a única das onze **já implementada**: `purchasing.supplier.related_party` e
`related_party_note` existem na `V22`, com `CHECK (related_party OR related_party_note IS NULL)` garantindo que
a observação só exista quando a marcação existe, e sem nenhum comportamento associado. Se o proprietário
confirmar A, o I2 fica integralmente aderente; se decidir diferente, o ajuste é aditivo.

---

### D11 — Nota sem duplicatas (à vista)

**Problema de negócio.** Nota sem duplicata não diz quando vence. O sistema presume pagamento à vista, ou
pergunta?

**Alternativa A (recomendada pela DR).** O usuário informa vencimento e parcelas na confirmação; sem duplicata
não se presume pagamento nem vencimento.

**Alternativas.** A DR não registra alternativa; as implícitas são presumir pago na data de emissão, ou
presumir vencimento igual à emissão.

**Consequências.** *Estoque/custo médio*: nenhuma. *Compras*: um passo a mais na confirmação dessas notas.
*Financeiro*: `finance.payable` exige `due_date` **não nulo** — então presumir nada é tecnicamente impossível
sem alguém informar a data. A alternativa "presumir pago" é a pior: marcaria como paga uma dívida que ninguém
quitou, o que o briefing já proíbe ("XML com cobrança/pagamento não marca conta como paga").

**Riscos operacionais.** Baixos. Bloqueia apenas a importação de notas sem cobrança.

**Recomendação técnica.** **A**, sem ressalva. É a única opção compatível com a restrição já decidida pelo
proprietário e com a obrigatoriedade de `due_date` no modelo financeiro existente.

---

### Resumo da recomendação técnica

| Decisão | Recomendo | Risco se decidida errado | Bloqueia |
| --- | --- | --- | --- |
| D1 custo de aquisição | A + composição gravada e auditável | **Alto** — contamina custo médio e margem | I4 |
| D2 rateio | A + asserção de fechamento + método parametrizável | Médio | I4 |
| D3 divergência | A, quantidade recebida sempre | **Alto** — saldo que não existe | I4 |
| D4 obrigações | A + vínculo compra↔conta por migration + idempotência por chave+duplicata | **Alto** — duplicar contas a pagar | I3 |
| D5 reimportação | A + hash sempre gravado, divergência bloqueante | **Alto** — compra em dobro | I3 |
| D6 vínculo de itens | A + vínculo por fornecedor/código, nunca descrição | **Alto** — custo no produto errado | I3 |
| D7 unidades | A + originais preservadas, fator no vínculo | **Crítico** — saldo e custo errados por fator | I3/I4 |
| D8 segregação | A, com a ressalva de atrito | Baixo, reversível | I5 |
| D9 CNPJ próprio | A, alerta e não bloqueio | Médio | I3 |
| D10 relacionado | A — **já implementada na V22** | Baixo | I2 |
| D11 nota à vista | A | Baixo | I3 (notas sem cobrança) |

As quatro que mais importam para o seu objetivo imediato — importar a NF-e 21 sem duplicar estoque nem contas a
pagar — são **D4, D5, D7 e D6**, nessa ordem.

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
- 2026-10-13 — acrescentada a seção "Análise técnica por decisão (D1–D11)" a pedido do proprietário, com problema de
  negócio, alternativas, consequências em estoque/custo médio/compras/financeiro, riscos e recomendação de cada
  decisão. **Nada foi decidido**: a `Decisão final` permanece `PENDENTE` e `I3`/`I4`/`I5` continuam bloqueados.
