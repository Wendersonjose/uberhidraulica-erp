# DR-0013 — Diagnóstico, desconto no orçamento, registro interno da decisão e status automático

- Tipo: `DOMAIN` / `FINANCIAL`
- Status: `DECIDED_PROVISIONALLY` — aguardando ratificação do Owner
- Task: `TASK-0013`
- Origem: `AG-03 — Domínio Oficina` / `AG-06 — Financeiro`
- Responsável pela decisão: proprietário do produto
- Criada em: `2026-09-17`
- Decidida provisoriamente em: `2026-09-17`, sob delegação explícita do Owner

## Problema

O cartão "Diagnóstico e orçamento da OS" pede diagnóstico técnico e "descontos quando permitidos";
o cartão "Aprovação e reprovação de orçamento" pede registrar a decisão do cliente, com data/hora,
preservando o orçamento apresentado e refletindo automaticamente no status da OS.

Hoje:

- a OS não tem campo de diagnóstico;
- o item do orçamento só tem quantidade e preço unitário;
- a decisão só pode ser registrada pelo próprio cliente, pelo link público;
- a OS não reage à apresentação nem à decisão.

## Decisão provisória

1. **Diagnóstico** é um texto da OS, editável enquanto a OS está em etapa operacional. Registrar o
   primeiro diagnóstico de uma OS `ABERTA` move a OS para o status padrão de `EM_DIAGNOSTICO`.
2. **Desconto por item, em valor**, faz parte da condição comercial versionada:

   ```text
   bruto    = arredondar(quantidade × preço unitário, 2, HALF_UP)   (DR-0007)
   total    = bruto − desconto
   0 ≤ desconto ≤ bruto, desconto com no máximo duas casas
   ```

   Alterar o desconto cria nova versão comercial, como preço e quantidade. Informar desconto maior
   que zero exige a permissão `QUOTE_DISCOUNT` (Dono e Gerente Administrativo na carga inicial).
3. **Registro interno da decisão**: um usuário com `QUOTE_PRESENT` registra a decisão que o cliente
   deu pessoalmente, por telefone ou mensagem. Fica gravado o canal de contato, o nome de quem
   autorizou (opcional), observações, o usuário e o instante do servidor. Usa as mesmas regras da
   decisão pública: revisão apresentada, dentro da validade, item não decidido e não obsoleto; uma
   decisão por versão comercial, qualquer que seja o canal.
4. **Status automático da OS** (desligável por regra):
   - apresentação de revisão → `AGUARDANDO_APROVACAO`;
   - ao menos um item aprovado na revisão → `APROVADA`;
   - todos os itens da revisão decididos e nenhum aprovado → `REPROVADA`.

   As regras só atuam enquanto a OS está em `ABERTA`, `EM_DIAGNOSTICO`, `AGUARDANDO_APROVACAO`,
   `APROVADA` ou `REPROVADA`; nunca tiram uma OS de execução ou de encerramento.
5. Reprovação não exclui nem cancela a OS.

## Pergunta final

Confirma desconto em valor por item com permissão própria, registro interno da decisão e as
regras automáticas de status descritas?
