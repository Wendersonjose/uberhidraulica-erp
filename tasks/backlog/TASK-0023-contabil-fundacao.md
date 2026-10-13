# TASK-0023 — Contábil: fundação (competência, plano de contas, partidas dobradas, razão, balancete)

## Identificação

- Status: `BACKLOG` (depende de `TASK-0021` e `TASK-0022`)
- Criada em: `2026-10-13`
- Origem: briefing do proprietário de 2026-10-13; `DR-0021` (fundação, não contabilização fiscal definitiva)
- Proprietário principal: a definir por DR (o `AG-06` cobre Financeiro; não há agente contábil — `AG-07` cobre conciliação)

## Escopo

Módulo separado `accounting`: competências mensais (aberta/fechada/reaberta com motivo), categorias contábeis e plano de contas
parametrizável, lançamentos de partidas dobradas (débito = crédito por lançamento), vínculo documento de origem × obrigação ×
lançamento, razão e balancete, conciliação bancária integrada ao Financeiro (OFX genérico), trilha de auditoria, relatórios de
apoio ao contador.

## Referência (somente conceito)

O "contábil" do `uberhidraulica-admin` é conciliação bancária por competência (OFX com FITID, hash do arquivo, validação de
fechamento, reabertura com motivo, auditoria antes/depois) — reaproveitável para competência e conciliação. **Não** há partidas
dobradas, plano de contas, razão nem balancete lá: essa parte é nova.

## Bloqueios e limites

- Regime tributário, plano de contas padrão e regras de contabilização dependem do **contador**: nada é fixado em código;
  tudo parametrizável, rotulado "não validado" e sem uso em produção antes da validação.
- Exige DR de: plano de contas inicial, política de fechamento/reabertura, vínculo com o regime tributário, tratamento de
  estornos e de lançamentos retroativos em competência fechada.
- Layouts reais de extrato (Itaú/Rede) não foram fornecidos (`DR-0021` §4): só OFX genérico.
