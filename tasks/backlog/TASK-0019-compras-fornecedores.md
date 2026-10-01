# TASK-0019 — Compras e Fornecedores

## Identificação

- Status: `IN_PROGRESS`
- Prioridade: alta
- Criada em: `2026-10-01`
- Origem: `AG-05 — Compras & Fornecedores`
- Proprietário principal: Compras — `AG-05`

## Objetivo

Entregar a primeira vertical utilizável de Compras & Fornecedores, com backend, banco e frontend funcionando juntos desde o início. A Task não será considerada concluída com API isolada ou telas mockadas sem integração real.

## Escopo MVP desta Task

- cadastro de fornecedores PF/PJ, contatos e condições comerciais básicas;
- fornecedor ativo/inativo sem exclusão física de histórico;
- pedido de compra com fornecedor, itens, quantidades, preços, frete, descontos, condição de pagamento e origem;
- vínculo de itens do pedido com o catálogo de produtos;
- estados comerciais explícitos do pedido;
- recebimento total e parcial;
- divergência de quantidade/preço registrada e auditável, com justificativa quando aceita;
- recebimento confirmado integrado ao Estoque, que permanece proprietário do saldo e do custo médio;
- geração da obrigação financeira via contrato/evento para o módulo Financeiro, sem Compras controlar quitação;
- histórico de preços por fornecedor sem sobrescrita;
- permissões específicas de compras;
- frontend integrado à API para fornecedores, pedidos e recebimento.

## Frontend obrigatório

A entrega deverá incluir navegação funcional no SaaS com:

- menu `Compras`;
- tela de Fornecedores com busca, cadastro, edição e ativação/inativação;
- tela de Pedidos de Compra com listagem, filtros e criação/edição enquanto permitido pelo estado;
- detalhe do pedido com itens, totais, condição comercial e histórico;
- fluxo de recebimento com quantidade recebida, saldo pendente e divergências;
- feedback visual de loading, vazio, erro e sucesso;
- ações condicionadas às permissões retornadas pelo backend;
- testes de componentes/fluxos e build TypeScript/lint verdes.

## Regras de domínio incorporadas do AG-05

- necessidade não é pedido;
- pedido não é recebimento;
- recebimento não é pagamento;
- criar pedido não aumenta estoque;
- somente recebimento físico confirmado gera entrada no Estoque;
- Compras informa custo de aquisição, mas o Estoque calcula custo médio;
- histórico de fornecedor, proposta e preço não é apagado por alterações posteriores;
- pedido e pagamento possuem estados independentes;
- recebimento parcial deve preservar quantidade recebida e pendente;
- divergência aceita exige justificativa;
- fornecedor inativo permanece em registros históricos;
- concorrência não pode permitir recebimento acima do saldo pendente sem tratamento explícito.

## Fora desta primeira vertical

Ficam para Task posterior, salvo dependência técnica inevitável:

- cotação completa com múltiplos fornecedores;
- score e comparação automática de propostas;
- carteira/crédito comercial avançado do fornecedor;
- devolução e substituição completas;
- consolidação documental fiscal avançada;
- automações de reserva para OS.

## Critérios de aceite

- [ ] migration Flyway posterior à V21 cria o modelo de Compras sem alterar o schema `public` legado;
- [ ] módulo de Compras respeita fronteiras do Spring Modulith;
- [ ] CRUD de fornecedor funcional no backend e frontend;
- [ ] pedido de compra funcional com itens do catálogo e máquina de estados;
- [ ] recebimento parcial e total funcionais;
- [ ] integração de recebimento com estoque testada em PostgreSQL real;
- [ ] integração comercial com Financeiro preserva separação entre compra e pagamento;
- [ ] histórico de preço por fornecedor preservado;
- [ ] permissões de Compras protegidas no backend e refletidas na UI;
- [ ] telas de Fornecedores, Pedidos e Recebimento operacionais contra API real;
- [ ] testes backend, integração PostgreSQL e frontend verdes;
- [ ] TypeScript, lint e build frontend verdes;
- [ ] nenhuma alteração nas tabelas legadas de salários em `public`.

## Histórico

- 2026-10-01 — Task criada após conclusão da TASK-0016 e merge da sprint anterior em `main`.
- 2026-10-01 — Owner definiu que o frontend deve fazer parte da entrega funcional desde o início, não como etapa posterior.
