# DECISION REQUEST — Template de Solicitação de Decisão

## 1. Identificação

ID: `DR-XXXX`

Título:

Tipo:

```text
BUSINESS
ARCHITECTURE
SECURITY
DATA
FINANCIAL
FISCAL
SCOPE
```

Status:

```text
OPEN
UNDER_ANALYSIS
DECIDED
CANCELLED
```

Criado em:

Criado por:

Última atualização:

---

## 2. Task relacionada

Task principal:

```text
TASK-XXXX
```

Outras tasks impactadas:

```text
-
```

---

## 3. Origem

Agente que identificou a necessidade:

```text
AG-XX — Nome do agente
```

Módulo relacionado:

```text
-
```

---

## 4. Problema

Descrever objetivamente qual decisão está faltando.

O problema deve responder:

> O que não está definido e por que isso impede ou pode alterar a implementação?

Problema:

```text
-
```

---

## 5. Contexto

Descrever o contexto necessário para entender a decisão.

Incluir quando aplicável:

- fluxo atual;
- regra relacionada;
- comportamento esperado;
- dados envolvidos;
- módulos afetados;
- decisão anterior relacionada.

Contexto:

```text
-
```

---

## 6. Motivo da Decision Request

Marcar o motivo principal:

- [ ] regra de negócio ausente;
- [ ] regra contraditória;
- [ ] alteração de requisito;
- [ ] alteração arquitetural;
- [ ] risco financeiro;
- [ ] risco fiscal;
- [ ] risco de segurança;
- [ ] problema de persistência;
- [ ] expansão de escopo;
- [ ] conflito entre módulos;
- [ ] comportamento de integração externa;
- [ ] outro.

Detalhes:

```text
-
```

---

## 7. Decisão atual relacionada

Existe decisão anterior relacionada?

```text
SIM | NÃO
```

Se sim:

```text
ID:
Tipo:
Resumo:
Status:
```

Exemplo:

```text
ADR-004
DECIDED
Monólito modular com Spring Modulith
```

---

## 8. Requisitos relacionados

```text
REQ-...
REQ-...
```

Ou:

```text
-
```

---

## 9. Regras de negócio relacionadas

### RN-01

```text
-
```

### RN-02

```text
-
```

---

## 10. Opções identificadas

Devem existir opções claras sempre que possível.

### Opção A

Descrição:

```text
-
```

Vantagens:

```text
-
```

Desvantagens:

```text
-
```

Impactos:

```text
-
```

Riscos:

```text
-
```

---

### Opção B

Descrição:

```text
-
```

Vantagens:

```text
-
```

Desvantagens:

```text
-
```

Impactos:

```text
-
```

Riscos:

```text
-
```

---

### Opção C

Descrição:

```text
-
```

Vantagens:

```text
-
```

Desvantagens:

```text
-
```

Impactos:

```text
-
```

Riscos:

```text
-
```

---

## 11. Recomendação do agente

O agente pode recomendar uma opção.

Essa recomendação não equivale à decisão final.

Recomendação:

```text
-
```

Justificativa:

```text
-
```

---

## 12. Impacto funcional

A decisão altera:

- [ ] fluxo de usuário;
- [ ] regra de negócio;
- [ ] estado;
- [ ] aprovação;
- [ ] garantia;
- [ ] orçamento;
- [ ] OS;
- [ ] cadastro;
- [ ] relatório;
- [ ] nenhum.

Detalhes:

```text
-
```

---

## 13. Impacto financeiro

A decisão afeta:

- [ ] preço;
- [ ] desconto;
- [ ] custo;
- [ ] receita;
- [ ] despesa;
- [ ] comissão;
- [ ] conta a pagar;
- [ ] conta a receber;
- [ ] pagamento;
- [ ] recebimento;
- [ ] saldo;
- [ ] caixa;
- [ ] cartão;
- [ ] margem;
- [ ] lucro;
- [ ] imposto;
- [ ] nenhum.

Se houver impacto financeiro:

AG-06 deve participar quando aplicável.

Detalhes:

```text
-
```

---

## 14. Impacto de estoque

A decisão afeta:

- [ ] saldo físico;
- [ ] saldo reservado;
- [ ] saldo disponível;
- [ ] custo médio;
- [ ] inventário;
- [ ] ajuste;
- [ ] perda;
- [ ] nenhum.

Detalhes:

```text
-
```

---

## 15. Impacto fiscal

A decisão afeta:

- [ ] NFS-e;
- [ ] DPS;
- [ ] documento fiscal;
- [ ] tributação;
- [ ] certificado;
- [ ] cancelamento;
- [ ] integração fiscal;
- [ ] nenhum.

Se houver impacto fiscal:

AG-08 deve participar.

Detalhes:

```text
-
```

---

## 16. Impacto de segurança

A decisão afeta:

- [ ] autenticação;
- [ ] autorização;
- [ ] permissão;
- [ ] ação crítica;
- [ ] dados pessoais;
- [ ] dados financeiros;
- [ ] segredo;
- [ ] certificado;
- [ ] link público;
- [ ] nenhum.

Se houver impacto relevante:

AG-09 deve participar.

Detalhes:

```text
-
```

---

## 17. Impacto arquitetural

A decisão altera:

- [ ] módulo;
- [ ] fronteira;
- [ ] contrato público;
- [ ] evento;
- [ ] persistência;
- [ ] integração;
- [ ] transação;
- [ ] infraestrutura;
- [ ] dependência externa;
- [ ] nenhum.

Se houver impacto arquitetural:

AG-02 deve participar.

Detalhes:

```text
-
```

---

## 18. Impacto em banco de dados

Existe impacto de persistência?

```text
SIM | NÃO
```

Se sim:

Conceitos afetados:

```text
-
```

Migração necessária:

```text
SIM | NÃO
```

Histórico precisa ser preservado?

```text
SIM | NÃO
```

Risco de perda de dados:

```text
BAIXO | MÉDIO | ALTO | CRÍTICO
```

AG-10 deve participar quando necessário.

---

## 19. Impacto em integrações externas

Integrações afetadas:

- [ ] Itaú;
- [ ] Rede;
- [ ] NFS-e;
- [ ] Object Storage;
- [ ] nenhuma;
- [ ] outra.

Detalhes:

```text
-
```

Avaliar:

- [ ] idempotência;
- [ ] retry;
- [ ] timeout;
- [ ] external_id;
- [ ] reprocessamento;
- [ ] contingência;
- [ ] duplicidade;
- [ ] logs.

---

## 20. Módulos impactados

Marcar os módulos impactados.

- [ ] IAM
- [ ] CRM
- [ ] Oficina
- [ ] Catálogo
- [ ] Estoque
- [ ] Compras
- [ ] Financeiro
- [ ] Conciliação
- [ ] Comissão
- [ ] Fiscal
- [ ] Rentabilidade
- [ ] Auditoria
- [ ] Integrações

Detalhes:

```text
-
```

---

## 21. Agentes envolvidos na análise

### Governança

- [ ] AG-00 — Orquestrador
- [ ] AG-01 — Produto & Requisitos
- [ ] AG-02 — Arquitetura
- [ ] AG-15 — Revisor Técnico

### Domínio

- [ ] AG-03 — Domínio Oficina
- [ ] AG-04 — Catálogo & Estoque
- [ ] AG-05 — Compras & Fornecedores
- [ ] AG-06 — Financeiro, Comissão & Rentabilidade
- [ ] AG-07 — Conciliação & Integrações Financeiras
- [ ] AG-08 — Fiscal
- [ ] AG-09 — Segurança & Auditoria

### Engenharia

- [ ] AG-10 — Banco de Dados
- [ ] AG-11 — Backend Spring
- [ ] AG-12 — Frontend React
- [ ] AG-13 — QA & Testes
- [ ] AG-14 — DevOps

---

## 22. Responsável pela decisão

Responsável principal:

```text
AG-XX
```

Quando aplicável:

```text
PROPRIETÁRIO DO PRODUTO
```

---

## 23. Necessita decisão do proprietário?

```text
SIM | NÃO
```

Deve ser `SIM` quando houver, por exemplo:

- mudança de regra de negócio;
- expansão de escopo;
- impacto financeiro não definido;
- mudança operacional relevante;
- conflito entre regras aprovadas;
- decisão comercial;
- decisão de comissão;
- decisão de garantia;
- decisão sobre cobrança.

---

## 24. Impacto se nenhuma decisão for tomada

Descrever o que acontece se a decisão permanecer aberta.

Exemplo:

```text
A implementação permanece bloqueada porque o comportamento
pode produzir resultados financeiros diferentes.
```

Impacto:

```text
-
```

---

## 25. Task deve ser bloqueada?

```text
SIM | NÃO
```

Se sim:

```text
STATUS DA TASK = BLOCKED
```

Motivo:

```text
-
```

---

## 26. Prazo da decisão

Existe prazo operacional?

```text
SIM | NÃO
```

Se sim:

Data limite:

```text
-
```

Motivo:

```text
-
```

---

## 27. Evidências

Listar evidências utilizadas na análise.

Podem incluir:

- requisito;
- print;
- fluxo da oficina;
- documento;
- contrato;
- API;
- legislação;
- logs;
- teste;
- comportamento atual.

Evidências:

```text
-
```

---

## 28. Pergunta final para decisão

Escrever uma única pergunta objetiva que permita resolver a Decision Request.

Exemplo:

```text
Quando um serviço já pago em comissão for reaberto por garantia,
a comissão anterior deve ser descontada automaticamente,
ajustada manualmente ou permanecer inalterada?
```

Pergunta:

```text
-
```

---

# 29. Análise dos agentes

## AG-01 — Produto & Requisitos

Análise:

```text
-
```

Recomendação:

```text
-
```

---

## AG-02 — Arquitetura

Aplicável?

```text
SIM | NÃO
```

Análise:

```text
-
```

Recomendação:

```text
-
```

---

## AG-03 a AG-09 — Domínio

Agente:

```text
AG-XX
```

Análise:

```text
-
```

Recomendação:

```text
-
```

---

## AG-10 — Banco de Dados

Aplicável?

```text
SIM | NÃO
```

Análise:

```text
-
```

---

## AG-15 — Revisor Técnico

Aplicável?

```text
SIM | NÃO
```

Observações:

```text
-
```

---

# 30. Decisão final

Status:

```text
DECIDED
```

Opção escolhida:

```text
A | B | C | OUTRA
```

Decisão:

```text
-
```

---

## 31. Justificativa da decisão

```text
-
```

---

## 32. Responsável pela decisão final

Nome / Papel:

```text
-
```

Agente:

```text
AG-XX | PROPRIETÁRIO DO PRODUTO
```

---

## 33. Data da decisão

```text
AAAA-MM-DD
```

---

## 34. Regras resultantes

Após a decisão, registrar as regras oficiais.

### Regra 1

```text
-
```

### Regra 2

```text
-
```

### Regra 3

```text
-
```

---

## 35. Requisitos que precisam ser atualizados

```text
-
```

---

## 36. Documentação que precisa ser atualizada

Marcar:

- [ ] docs/requirements
- [ ] docs/domain
- [ ] docs/architecture
- [ ] docs/api
- [ ] ADR
- [ ] Task
- [ ] testes
- [ ] nenhuma.

Detalhes:

```text
-
```

---

## 37. Tasks impactadas após decisão

```text
TASK-...
TASK-...
```

Ou:

```text
-
```

---

## 38. Status das tasks após decisão

Task:

```text
TASK-XXXX
```

Novo status:

```text
READY | BACKLOG | CANCELLED | CONTINUA_BLOCKED
```

---

## 39. ADR necessária?

```text
SIM | NÃO
```

Se sim:

```text
ADR-XXXX
```

---

## 40. Migration necessária?

```text
SIM | NÃO
```

Se sim:

Descrição:

```text
-
```

---

## 41. Testes necessários após decisão

```text
-
```

---

## 42. Handoff necessário

```text
SIM | NÃO
```

Se sim:

Origem:

```text
AG-XX
```

Destino:

```text
AG-XX
```

Objetivo:

```text
-
```

---

## 43. Risco residual

Após a decisão existe risco residual?

```text
SIM | NÃO
```

Se sim:

Risco:

```text
-
```

Probabilidade:

```text
BAIXA | MÉDIA | ALTA
```

Impacto:

```text
BAIXO | MÉDIO | ALTO | CRÍTICO
```

Mitigação:

```text
-
```

---

## 44. Histórico

### Versão 1

Data:

Alteração:

Responsável:

```text
-
```

---

## 45. Encerramento

Status final:

```text
DECIDED | CANCELLED
```

Todas as tasks relacionadas foram atualizadas?

```text
SIM | NÃO
```

Documentação foi atualizada?

```text
SIM | NÃO
```

Decisão comunicada ao AG-00?

```text
SIM | NÃO
```

---

## 46. Regra final

Uma Decision Request existe para impedir que agentes preencham lacunas importantes por conta própria.

Se uma escolha puder alterar:

- comportamento da oficina;
- dinheiro;
- estoque;
- comissão;
- garantia;
- fiscal;
- segurança;
- arquitetura;
- escopo;

e a regra não estiver definida:

**NÃO IMPLEMENTAR POR SUPOSIÇÃO.**

Registrar, analisar e decidir.