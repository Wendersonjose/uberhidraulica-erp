# AGENTS.md — Governança do Uber-Hidráulica ERP

## Objetivo

Este arquivo é o ponto de entrada da governança multiagente do projeto.

As especificações completas de cada agente estão em:

```text
agents/
```

Este documento funciona como:

```text
índice;
roteador;
regra de precedência;
referência rápida.
```

Ele não substitui os documentos individuais dos agentes.

---

## Princípio de Governança

Nenhuma feature relevante deve saltar diretamente de uma ideia para implementação.

Fluxo esperado:

```text
necessidade
↓
Task
↓
requisito
↓
domínio
↓
arquitetura
↓
segurança quando aplicável
↓
dados
↓
backend
↓
frontend
↓
QA
↓
revisão independente
↓
encerramento
```

Quando houver ambiguidade relevante:

```text
DECISION_REQUEST
```

deve ser criada antes de inventar uma regra.

---

# Agentes

## AG-00 — Orquestrador

Arquivo:

```text
agents/AG-00-orquestrador.md
```

Responsabilidade:

```text
orquestrar fluxo;
controlar handoffs;
controlar status;
impedir saltos de governança;
encerrar Tasks.
```

Não deve:

```text
inventar regra de negócio;
implementar domínio;
alterar arquitetura por conta própria.
```

---

## AG-01 — Produto & Requisitos

Arquivo:

```text
agents/AG-01-produto-requisitos.md
```

Responsabilidade:

```text
objetivo;
escopo;
regras funcionais;
critérios de aceite;
MVP;
Decision Requests funcionais.
```

---

## AG-02 — Arquitetura

Arquivo:

```text
agents/AG-02-arquitetura.md
```

Responsabilidade:

```text
fronteiras modulares;
contratos;
arquitetura;
dependências;
integração entre módulos;
decisões técnicas estruturais.
```

---

## AG-03 — Domínio Oficina

Arquivo:

```text
agents/AG-03-dominio-oficina.md
```

Responsabilidade:

```text
OS;
orçamento;
aprovação;
execução;
garantia;
workflow;
invariantes do domínio Oficina.
```

---

## AG-04 — Catálogo & Estoque

Arquivo:

```text
agents/AG-04-catalogo-estoque.md
```

Responsabilidade:

```text
catálogo;
peças;
componentes;
aplicações;
estoque;
reservas;
inventário;
custos.
```

---

## AG-05 — Compras & Fornecedores

Arquivo:

```text
agents/AG-05-compras-fornecedores.md
```

Responsabilidade:

```text
fornecedores;
necessidades de compra;
cotações;
pedidos;
recebimentos;
devoluções;
conta corrente de fornecedor.
```

---

## AG-06 — Financeiro

Arquivo:

```text
agents/AG-06-financeiro.md
```

Responsabilidade funcional:

```text
financeiro;
comissões;
rentabilidade;
precificação.
```

---

## AG-07 — Conciliação & Integrações Financeiras

Arquivo:

```text
agents/AG-07-conciliacao-integracoes-financeiras.md
```

Responsabilidade:

```text
Itaú;
Rede;
OFX;
CSV;
conciliação;
classificação de movimentos.
```

---

## AG-08 — Fiscal

Arquivo:

```text
agents/AG-08-fiscal.md
```

Responsabilidade:

```text
NFS-e;
DPS;
documentos fiscais;
integração fiscal;
histórico fiscal.
```

---

## AG-09 — Segurança & Auditoria

Arquivo:

```text
agents/AG-09-seguranca-auditoria.md
```

Responsabilidade:

```text
autenticação;
autorização;
permissões;
sessões;
links públicos;
segredos;
dados pessoais;
auditoria;
ameaças;
controles de segurança.
```

Deve participar antes da persistência/backend quando uma decisão de segurança altera:

```text
modelo de dados;
contrato;
token;
escopo;
identidade;
evidência.
```

---

## AG-10 — Banco de Dados

Arquivo:

```text
agents/AG-10-banco-dados.md
```

Responsabilidade:

```text
PostgreSQL;
modelo relacional;
constraints;
índices;
integridade;
Flyway;
concorrência no nível de persistência.
```

---

## AG-11 — Backend Spring

Arquivo:

```text
agents/AG-11-backend-spring.md
```

Responsabilidade:

```text
Spring Boot;
use cases;
REST;
ports;
adapters;
transações;
persistência;
eventos;
outbox.
```

---

## AG-12 — Frontend React

Arquivo:

```text
agents/AG-12-frontend-react.md
```

Responsabilidade:

```text
React;
TypeScript;
fluxos de interface;
estado de servidor;
formulários;
validação;
acessibilidade;
responsividade.
```

---

## AG-13 — QA & Testes

Arquivo:

```text
agents/AG-13-qa-testes.md
```

Responsabilidade:

```text
estratégia de testes;
rastreabilidade;
Testcontainers;
API tests;
security tests;
concurrency tests;
E2E;
quality gates.
```

---

## AG-14 — DevOps

Arquivo:

```text
agents/AG-14-devops.md
```

Responsabilidade:

```text
Docker;
CI/CD;
ambientes;
deploy;
HTTPS;
logs de infraestrutura;
segredos operacionais;
observabilidade.
```

---

## AG-15 — Revisor Técnico

Arquivo:

```text
agents/AG-15-revisor-tecnico.md
```

Responsabilidade:

```text
revisão independente;
consistência;
findings;
riscos;
rastreabilidade;
aprovação técnica final.
```

AG-15 não deve aprovar automaticamente algo apenas porque outros agentes marcaram:

```text
APPROVED.
```

---

# Fluxo Genérico

Fluxo de referência:

```text
AG-00
↓
AG-01
↓
Agente de domínio responsável
↓
AG-02
↓
AG-09 quando aplicável
↓
AG-10 quando houver persistência
↓
AG-11
↓
AG-12
↓
AG-13
↓
AG-14 quando houver impacto de infraestrutura/deploy
↓
AG-15
↓
AG-00
```

A ordem pode ser ajustada pelo AG-00 quando houver dependência técnica explícita.

Não pode ser ajustada para:

```text
eliminar revisão;
ignorar requisito;
ignorar segurança;
ignorar Decision Request bloqueadora.
```

---

# Decision Requests

Diretório:

```text
decision-requests/
```

Template:

```text
decision-requests/DECISION-REQUEST-TEMPLATE.md
```

Quando houver ambiguidade relevante, registrar:

```text
problema;
opções;
impactos;
riscos;
recomendação;
responsável pela decisão;
decisão final.
```

---

# Tasks

Diretório:

```text
tasks/
```

Templates:

```text
tasks/TASK-TEMPLATE.md

tasks/HANDOFF-TEMPLATE.md
```

Estados conceituais:

```text
BACKLOG

IN_PROGRESS

REVIEW

DONE
```

Uma Task documental pode utilizar:

```text
SPECIFICATION_DONE
```

quando a especificação foi concluída mas a implementação ainda não existe.

---

# Definition of Done

Nunca interpretar:

```text
documentação aprovada
```

como:

```text
código implementado.
```

Uma feature de produção só poderá ser considerada implementada depois das evidências aplicáveis:

```text
backend;
frontend;
migration;
testes;
security review;
CI;
deploy/configuração quando aplicável.
```

---

# Fronteiras Arquiteturais

O projeto segue:

```text
Monólito Modular
Spring Modulith
```

Regra:

```text
um módulo não acessa diretamente repository,
entity ou tabela interna de outro módulo.
```

Comunicação deve ocorrer por:

```text
Application Contracts;
interfaces públicas;
Domain Events;
Integration Events.
```

---

# Backend

Fluxo obrigatório:

```text
Controller
↓
Application / Use Case
↓
Domain
↓
Repository Port
↓
Persistence Adapter
```

Evitar:

```text
Controller → JpaRepository
```

---

# Segurança

Nunca versionar:

```text
senhas;
tokens;
API keys;
certificados privados;
A1;
arquivos .pfx;
arquivos .p12;
.env real.
```

A autenticação interna planejada utiliza:

```text
Spring Security;
session;
Secure HttpOnly Cookie.
```

---

# Banco

Banco planejado:

```text
PostgreSQL
```

Migrations:

```text
Flyway
```

Dinheiro:

```text
BigDecimal no Java;
NUMERIC no PostgreSQL.
```

---

# Testes

Quando persistência PostgreSQL for relevante:

```text
PostgreSQL
+
Testcontainers
```

Não utilizar H2 como substituto de comportamento do PostgreSQL.

---

# TASK-0001

Primeira simulação completa:

```text
TASK-0001 — Aprovação Parcial de Orçamento
```

Status:

```text
SPECIFICATION_DONE
```

Decision Request:

```text
DR-0001 — DECIDED — OPÇÃO B
```

Implementação:

```text
NOT_IMPLEMENTED
```

O objetivo da TASK-0001 foi validar o processo de governança antes do início da implementação real.

---

# Regra de Precedência

Em caso de conflito:

```text
1. decisão explícita do proprietário do produto;

2. Decision Request formalmente decidida;

3. requisito aprovado mais recente;

4. domínio aprovado mais recente;

5. arquitetura aprovada mais recente;

6. contratos técnicos aprovados;

7. documentos históricos anteriores.
```

Documentos históricos não devem ser apagados apenas por terem sido superados.

Eles devem ser claramente identificáveis como versões anteriores.

---

# Regra Final

Os agentes existem para reduzir:

```text
ambiguidade;
acoplamento;
regressão;
decisão implícita;
implementação sem contrato.
```

O objetivo não é produzir documentação por volume.

O objetivo é fazer com que:

```text
CÓDIGO
IMPLEMENTE
UMA DECISÃO
QUE JÁ FOI ENTENDIDA,
RASTREADA
E REVISADA.
```