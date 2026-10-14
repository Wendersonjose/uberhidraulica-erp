# DR-0021 — Escopo do piloto × módulos especificados e ainda não implementados

## 1. Identificação

```text
ID: DR-0021
Título: Fronteira do MVP/piloto e sequência dos módulos Compras, Comissões, Conciliação, Fiscal e Rentabilidade
Tipo: SCOPE
Status: DECIDED (2026-10-13) — ver §8
Criado em: 2026-10-05
Criado por: AG-00 — Orquestrador (TASK-0019)
Última atualização: 2026-10-13
```

## 2. Task relacionada

```text
Task principal: TASK-0019 — Fechamento do MVP/piloto para homologação
Bloqueia a Task: NÃO. Bloqueia a abertura de qualquer Task dos módulos listados abaixo.
```

## 3. Problema

Os documentos de governança descrevem um MVP **amplo** (`AG-01`, seções 128-134: NFS-e para mão de obra,
comissões, custos de funcionário; `AG-05` compras e fornecedores; `AG-07` conciliação Itaú/Rede), mas as
Tasks `TASK-0001` a `TASK-0018` entregaram o **piloto de uma oficina**: cadastros, OS, orçamento com aprovação
parcial, estoque, financeiro, caixa físico, permissões e deploy. A `TASK-0018` declara explicitamente como fora
do escopo: DRE contábil, NF-e/fiscal, custo de mão de obra, BI avançado, multi-tenant/SaaS.

Sem uma decisão do proprietário, "finalizar o MVP" tem dois significados incompatíveis: (a) o piloto
entregue, pronto para homologar; (b) o MVP descrito nos agentes, que exige módulos inteiros ainda sem Task.

## 4. Estado verificado de cada módulo (auditoria de 2026-10-05)

| Módulo | Especificação | Decision Request | Task | Código | Dependência externa / bloqueio |
| --- | --- | --- | --- | --- | --- |
| Oficina, CRM, Catálogo, Estoque, Orçamento, Financeiro (receber/pagar/fluxo/caixa), IAM | `AG-01/03/04/06/09` | `DR-0001`–`DR-0018` decididas | `TASK-0001`–`0018` DONE | **Entregue** | — |
| Compras e Fornecedores | `AG-05` (2.278 linhas) | nenhuma específica | nenhuma | **Não existe** | depende de Catálogo/Estoque/Financeiro (existem); recebimento fiscal depende de Fiscal |
| Comissões | `AG-06` §comissão + README (pool 30%, pesos por nível) | nenhuma | nenhuma | **Não existe** | exige cadastro de técnicos/níveis, base de cálculo do serviço e política de ajuste — regras a confirmar |
| Rentabilidade / Precificação | `AG-06` | nenhuma | nenhuma | Parcial: painel financeiro gerencial (faturamento, custo de peças, lucro bruto) | custo de mão de obra e rateio não definidos; ver `DR-0019` |
| Conciliação bancária (Itaú, Rede, OFX, CSV) | `AG-07` | nenhuma | nenhuma | **Não existe** | layouts reais de extrato/credenciais e regras de classificação |
| Fiscal / NFS-e (Uberlândia/MG) | `AG-08` | nenhuma | nenhuma | **Não existe** | certificado digital A1, contrato da API municipal/padrão nacional, ambiente de homologação fiscal, regras tributárias aprovadas — **todos externos** |
| Garantia avançada | `AG-03` | nenhuma | nenhuma | Parcial: dias de garantia por serviço copiados para a OS | regra de validade/acionamento não definida |
| Integrações externas (WhatsApp etc.) | — | — | — | — | **fora do MVP** (`AG-00` §24) |

## 5. Opções

- **A. O MVP é o piloto entregue (recomendado).** Homologar o que existe com a oficina; abrir Tasks separadas, em
  ordem decidida pelo proprietário, para cada módulo acima, cada uma com requisito → domínio → arquitetura →
  segurança → dados → backend → frontend → QA → revisão, como a governança exige.
- **B. O MVP inclui Fiscal (NFS-e).** Torna a emissão fiscal pré-requisito de produção. Exige os itens externos
  da tabela (certificado A1, API municipal, homologação fiscal) antes de qualquer código; sem eles a Task não
  pode ser concluída.
- **C. O MVP inclui Compras + Comissões.** Antes: confirmar as regras de comissão (`AG-06`/README) e abrir a
  Task de Compras; maior esforço, sem dependência externa.

## 6. Recomendação

**Opção A**, com a ordem sugerida: (1) decidir `DR-0019` e `DR-0020`; (2) Compras e Fornecedores (sem
dependência externa); (3) Comissões (confirmando regras); (4) Conciliação (com os layouts reais); (5) Fiscal
quando o certificado A1 e o ambiente de homologação do município estiverem disponíveis. Nenhum desses módulos
foi implementado na `TASK-0019`: não há contrato aprovado suficiente (Task, requisito, domínio e arquitetura
para o recorte) e implementá-los "para constar" violaria a regra de escopo do `AG-00` §24.

## 7. Impactos

```text
Funcional: define o que "MVP concluído" significa para o proprietário.
Cronograma: cada módulo é uma Task própria pelo fluxo de governança completo.
Infra/Segurança: Fiscal e Conciliação introduzem segredos (certificado A1, credenciais bancárias) e armazenamento
de arquivos; exigem Task própria de segredos operacionais (AG-14) antes de qualquer código.
```

## 8. Responsável e decisão

```text
Responsável pela decisão final: Proprietário do produto
Decisão final: DECIDIDA em 2026-10-13 — a Opção A permanece como definição de "MVP do piloto"; o proprietário
autoriza, além dela, a abertura imediata das Tasks de Compras e Fornecedores, evolução do Financeiro integrado a
Compras e fundação do Contábil, nesta ordem.
```

## 9. Decisão registrada (2026-10-13)

Origem: briefing do proprietário "Implementar Compras, Financeiro e Contábil no SaaS da Oficina", respondido em chat
(escolha "escopo completo"). Esta decisão libera, **respeitado o fluxo de governança do `AGENTS.md`** (Task →
requisito → domínio → arquitetura → segurança → dados → backend → frontend → QA → revisão):

| Módulo | Liberado | Task | Limites registrados |
| --- | --- | --- | --- |
| Compras e Fornecedores (inclui importação de NF-e modelo 55 como documento de **compra**) | SIM | `TASK-0021` | Importar XML não é receber nem pagar. A situação fiscal declarada no XML não substitui consulta oficial. |
| Financeiro integrado a Compras (parcelas, contas bancárias, centros de custo, fluxo previsto/realizado) | SIM | `TASK-0022` | Preserva os fluxos de `TASK-0015`/`0016`; evolui por migrations versionadas. |
| Contábil (competência, plano de contas parametrizável, partidas dobradas, razão, balancete, fechamento) | SIM, como **fundação** | `TASK-0023` | Classificação tributária e contabilização fiscal definitiva dependem do regime e do contador: ficam parametrizáveis e exigem validação antes de produção. |
| Conciliação bancária (OFX/CSV) integrada ao Contábil | SIM, como fundação | `TASK-0023` | Layouts reais de extrato ainda não foram fornecidos (premissa original do DR); só o formato OFX genérico é coberto. |
| Fiscal / NFS-e (emissão) | **NÃO** | — | Continua dependente de certificado A1 e homologação municipal (`AG-08`); não faz parte desta decisão. |
| Comissões, Rentabilidade/Precificação | **NÃO** | — | Fora desta decisão. |
| Multi-tenant / SaaS persistido | **NÃO** | — | O banco segue single-installation (ver `V21`); nenhum `tenant_id` é criado por esta decisão. Exige DR própria. |

Decisões de domínio de Compras que o `AG-05` §163 manda tratar por Decision Request estão em `DR-0022`.
