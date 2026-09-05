# HANDOFF — Template de Transferência entre Agentes

## 1. Identificação

ID do Handoff: `HANDOFF-XXXX`

Task relacionada: `TASK-XXXX`

Data:

Status:

```text
PENDING
ACCEPTED
COMPLETED
BLOCKED
```

---

## 2. Origem

Agente de origem:

```text
AG-XX — Nome do agente
```

Responsável pela entrega:

```text
-
```

---

## 3. Destino

Agente de destino:

```text
AG-XX — Nome do agente
```

Responsável esperado:

```text
-
```

---

## 4. Objetivo do Handoff

Descrever claramente por que a tarefa está sendo transferida.

Exemplo:

```text
Entregar ao AG-11 Backend Spring as regras, contratos e decisões
necessárias para implementação da aprovação parcial de orçamento.
```

Objetivo:

```text
-
```

---

## 5. Contexto resumido

Descrever apenas o contexto necessário para o agente de destino continuar o trabalho sem precisar reconstruir toda a história.

Contexto:

```text
-
```

---

## 6. Problema que está sendo tratado

Problema:

```text
-
```

---

## 7. Resultado esperado do agente de destino

O agente de destino deve produzir:

```text
-
```

Exemplo:

```text
- caso de uso;
- contratos;
- implementação;
- testes;
- migration;
- documentação;
```

---

## 8. Decisões já aprovadas

Listar apenas decisões confirmadas.

### Decisão 1

```text
-
```

### Decisão 2

```text
-
```

### Decisão 3

```text
-
```

O agente de destino não deve reinterpretar essas decisões.

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

### RN-03

```text
-
```

---

## 10. Requisitos relacionados

```text
REQ-...
REQ-...
```

Ou:

```text
-
```

---

## 11. Critérios de aceite relevantes

### CA-01

```text
DADO que
QUANDO
ENTÃO
```

### CA-02

```text
DADO que
QUANDO
ENTÃO
```

### CA-03

```text
DADO que
QUANDO
ENTÃO
```

---

## 12. Arquitetura relevante

Módulo proprietário:

```text
-
```

Módulos envolvidos:

```text
-
```

Contratos públicos:

```text
-
```

Eventos:

```text
-
```

Ports:

```text
-
```

Adapters:

```text
-
```

---

## 13. Persistência

Existe impacto em banco?

```text
SIM | NÃO
```

Se sim:

Entidades/conceitos:

```text
-
```

Migrations:

```text
-
```

Constraints:

```text
-
```

Índices:

```text
-
```

Histórico necessário:

```text
-
```

---

## 14. API

Existe impacto em API?

```text
SIM | NÃO
```

Endpoints:

```text
-
```

Requests:

```text
-
```

Responses:

```text
-
```

Erros:

```text
-
```

Permissões:

```text
-
```

---

## 15. Frontend

Existe impacto em frontend?

```text
SIM | NÃO
```

Telas:

```text
-
```

Componentes:

```text
-
```

Estados:

```text
-
```

Erros esperados:

```text
-
```

---

## 16. Segurança

A tarefa envolve:

- [ ] autenticação;
- [ ] autorização;
- [ ] permissão;
- [ ] ação crítica;
- [ ] dados financeiros;
- [ ] dados pessoais;
- [ ] segredo;
- [ ] certificado;
- [ ] link público;
- [ ] integração externa;
- [ ] nenhum.

Regras relevantes:

```text
-
```

---

## 17. Auditoria

É necessária auditoria?

```text
SIM | NÃO
```

Eventos auditáveis:

```text
-
```

Campos esperados:

```text
usuário
data/hora
ação
estado anterior
estado posterior
justificativa
```

Quando aplicável.

---

## 18. Impacto financeiro

Existe impacto financeiro?

```text
SIM | NÃO
```

Conceitos envolvidos:

- [ ] preço;
- [ ] desconto;
- [ ] custo;
- [ ] comissão;
- [ ] pagamento;
- [ ] recebimento;
- [ ] saldo;
- [ ] caixa;
- [ ] cartão;
- [ ] margem;
- [ ] lucro;
- [ ] nenhum.

Detalhes:

```text
-
```

---

## 19. Impacto de estoque

Existe impacto?

```text
SIM | NÃO
```

Conceitos:

- [ ] saldo físico;
- [ ] saldo reservado;
- [ ] saldo disponível;
- [ ] custo médio;
- [ ] inventário;
- [ ] ajuste;
- [ ] nenhum.

Detalhes:

```text
-
```

---

## 20. Integrações externas

Integrações envolvidas:

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

---

## 21. Artefatos produzidos pelo agente de origem

Listar tudo que já existe.

```text
-
```

Exemplos:

```text
docs/requirements/REQ-ORC-001.md
docs/architecture/ADR-004.md
migration planejada
contrato de evento
diagrama
```

---

## 22. Arquivos relevantes

```text
-
```

Exemplo:

```text
agents/AG-03-dominio-oficina.md
docs/domain/orcamento.md
tasks/backlog/TASK-0041-aprovacao-parcial.md
```

---

## 23. Pendências

Pendências não impeditivas:

```text
-
```

Pendências impeditivas:

```text
-
```

Se houver pendência impeditiva:

```text
STATUS DO HANDOFF = BLOCKED
```

---

## 24. Decision Requests relacionadas

```text
-
```

Exemplo:

```text
DR-0012 — DECIDED
DR-0015 — OPEN
```

---

## 25. Riscos identificados

### Risco 1

Descrição:

```text
-
```

Severidade:

```text
LOW | MEDIUM | HIGH | CRITICAL
```

Mitigação:

```text
-
```

---

### Risco 2

Descrição:

```text
-
```

Severidade:

```text
LOW | MEDIUM | HIGH | CRITICAL
```

Mitigação:

```text
-
```

---

## 26. Proibições específicas

O agente de destino não deve:

```text
-
```

Exemplo:

```text
- alterar regra de comissão;
- acessar repository interno de outro módulo;
- substituir evento por acesso direto ao banco;
- alterar requisito aprovado;
```

---

## 27. Próxima ação esperada

A próxima ação deve ser objetiva.

```text
-
```

Exemplo:

```text
Implementar o caso de uso de aprovação parcial no módulo Oficina,
respeitando as regras e eventos descritos neste handoff.
```

---

## 28. Critério de conclusão do agente de destino

O trabalho do agente de destino estará concluído quando:

- [ ] resultado esperado produzido;
- [ ] regras respeitadas;
- [ ] critérios de aceite atendidos;
- [ ] testes criados quando aplicável;
- [ ] documentação atualizada;
- [ ] novos riscos registrados;
- [ ] Decision Requests abertas quando necessário;
- [ ] handoff seguinte preparado quando necessário.

---

## 29. Aceite do agente de destino

Agente:

```text
AG-XX
```

Status:

```text
ACCEPTED | BLOCKED
```

Data:

Motivo de bloqueio, se houver:

```text
-
```

---

## 30. Resultado do Handoff

Status final:

```text
COMPLETED | BLOCKED | CANCELLED
```

Resultado produzido:

```text
-
```

Observações:

```text
-
```

---

## 31. Regra final

O agente de destino deve receber contexto suficiente para continuar o trabalho sem reconstruir decisões já tomadas.

Se faltar informação crítica:

**NÃO ASSUMIR.**

Bloquear o handoff e solicitar complemento ou abrir `DECISION_REQUEST`.