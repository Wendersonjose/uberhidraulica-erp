# Roteiro de homologação pela tela — ERP da oficina com Compras (V25)

Onde rodar: na **cópia migrada da produção** do passo 4b de
[`PREVOO-producao.md`](../architecture/devops/PREVOO-producao.md) (`rehearse-promotion.sh --keep` + `npm run dev`), com o seu login
real de Dono. Tudo o que for criado aqui morre com a cópia. **Não rode este roteiro na produção** antes da promoção: lá ainda não
existe Compras. Depois da promoção, só os itens marcados **[P]** são repetidos na produção, com dados identificáveis
(`ZZ HOMOLOG <data>`), e inativados/cancelados no fim.

Registre cada item como `OK` ou `FALHA: <o que viu>`. Uma falha em item marcado **(bloqueia)** é NO-GO.

## 1. Acesso e segurança

| # | Ação | Esperado |
| --- | --- | --- |
| 1.1 **[P] (bloqueia)** | Entrar com e-mail e senha reais | Painel abre; nome do Dono no topo |
| 1.2 **(bloqueia)** | Recarregar a página (F5) em `/clientes` | Continua logado e na mesma tela |
| 1.3 | Sair; tentar abrir `/ordens-servico` | Volta para a tela de login |
| 1.4 | Errar a senha 3 vezes; entrar com a certa | Mensagem genérica (não diz se o e-mail existe); entra normalmente |
| 1.5 | *Configurações → Usuários*: abrir um usuário não-Dono | Perfil e permissões visíveis; nada foi perdido na migração |

## 2. Dados existentes intactos (a migração não pode ter mexido em nada)

| # | Ação | Esperado |
| --- | --- | --- |
| 2.1 **[P] (bloqueia)** | *Clientes*: buscar 3 clientes que você conhece (1 PF, 1 PJ) | Mesmos dados, endereço e documentos de antes |
| 2.2 **[P] (bloqueia)** | *Veículos*: abrir 2 veículos | Placa, dono e situação iguais |
| 2.3 **[P] (bloqueia)** | *Ordens de serviço*: abrir 3 OS em situações diferentes (aberta, em execução, finalizada) | Itens, valores, orçamentos e histórico iguais |
| 2.4 **[P] (bloqueia)** | *Estoque*: 3 produtos com saldo | Saldo e custo médio iguais aos de antes |
| 2.5 **[P] (bloqueia)** | *Financeiro → Resumo financeiro*, *Contas a receber*, *Contas a pagar*, *Caixa físico* | Mesmos totais e títulos; o caixa aberto (se houver) continua aberto |

## 3. Oficina (regressão)

| # | Ação | Esperado |
| --- | --- | --- |
| 3.1 | Cliente PF novo com CPF inválido | Recusa com mensagem no campo |
| 3.2 | Cliente PJ novo + veículo | Salva; veículo aparece no cliente |
| 3.3 | Nova OS para o veículo; incluir 1 serviço e 1 peça com saldo | OS criada; totais corretos |
| 3.4 | Gerar orçamento, apresentar e abrir o link público em aba anônima | Página pública do orçamento abre sem login |
| 3.5 **(bloqueia)** | No link público, aprovar só parte dos itens | OS mostra aprovação parcial; só os itens aprovados seguem |
| 3.6 | Executar e finalizar a OS | Baixa de estoque só da peça aprovada; recebível gerado só do aprovado |
| 3.7 | Tentar alterar o orçamento da OS finalizada | Recusado |

## 4. Compras (novo)

| # | Ação | Esperado |
| --- | --- | --- |
| 4.1 **[P] (bloqueia)** | *Compras → Configurações de Compras*: informar o **CNPJ completo** do estabelecimento | Salvo |
| 4.2 **[P]** | *Financeiro → Configurações financeiras*: existe ao menos 1 categoria de despesa | Sim (a importação exige) |
| 4.3 | *Compras → Fornecedores*: novo fornecedor PJ | Salvo; CNPJ validado |
| 4.4 **(bloqueia)** | *Compras → Compras e NF-e → Importar NF-e*: enviar o XML de uma NF-e real recebida | Prévia com fornecedor, itens, totais e parcelas iguais ao DANFE |
| 4.5 | Na prévia, ligar cada item a um produto (ou criar) e o fator de unidade (KIT → unidades) | Aceita; custo unitário calculado |
| 4.6 **(bloqueia)** | Confirmar a importação | 1 compra; contas a pagar = parcelas da nota; **estoque ainda não mudou** |
| 4.7 **(bloqueia)** | Importar o **mesmo** XML de novo | Recusado como duplicado; nenhuma conta a pagar nova |
| 4.8 **(bloqueia)** | Na compra, *Receber agora* toda a mercadoria | Estoque entra só agora, ao custo de aquisição; custo médio atualizado |
| 4.9 | Estornar o recebimento | Saldo volta ao anterior; contas a pagar continuam |
| 4.10 | Receber parcialmente (parte dos itens) | Compra fica parcialmente recebida; saldo só do recebido |
| 4.11 | *Compras → Compras e NF-e → Novo pedido*: pedido com 2 itens; enviar para aprovação | Fica aguardando aprovação; **não** gera conta a pagar |
| 4.12 | Aprovar o pedido com o **mesmo** usuário que o criou | Recusado (segregação de funções) |
| 4.13 | Aprovar com outro usuário com permissão (ex.: gerente administrativo) | Aprovado |
| 4.14 **(bloqueia)** | Tentar *Receber agora* no pedido aprovado | Recusado: "receba na compra da nota importada" (DR-0023) |

## 5. Estoque

| # | Ação | Esperado |
| --- | --- | --- |
| 5.1 | Abrir o produto recebido em 4.8 | Movimento de entrada com origem "compra" e o número da nota |
| 5.2 | Entrada manual de 1 unidade em outro produto | Aceita; movimento manual registrado |
| 5.3 | Saída maior que o saldo | Recusada (saldo insuficiente) |

## 6. Financeiro

| # | Ação | Esperado |
| --- | --- | --- |
| 6.1 **(bloqueia)** | *Contas a pagar*: as parcelas da nota de 4.6 | Valores, vencimentos e fornecedor corretos; ligadas à compra |
| 6.2 | Pagar 1 parcela | Baixa registrada; painel atualiza |
| 6.3 | *Contas a receber*: receber o título da OS de 3.6 | Baixa; entra no caixa se for dinheiro |
| 6.4 | *Caixa físico*: abrir (se fechado), lançar, conferir e fechar | Saldo conferido bate; diferença registrada se houver |
| 6.5 | *Resumo financeiro* e *Fluxo de caixa* | Refletem 6.2–6.4 |

## Resultado

- Todos os itens **(bloqueia)** `OK` → homologação **aprovada** para o SHA ensaiado.
- Qualquer item **(bloqueia)** com falha → NO-GO; anote a tela, o passo e a mensagem.
- Depois da promoção, repetir só os **[P]** na produção.
