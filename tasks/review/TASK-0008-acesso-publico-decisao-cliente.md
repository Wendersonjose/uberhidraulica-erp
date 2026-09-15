# TASK-0008 — Acesso público e decisão do cliente sobre orçamento

## Identificação

- Status: `REVIEW`
- Prioridade: `CRITICAL`
- Criada em: `2026-09-15`
- Proprietário principal: Oficina — `AG-03`
- Responsável atual: `AG-11`

## Objetivo

Fechar o fluxo comercial especificado na `TASK-0001`: permitir que o cliente abra um link seguro, veja exatamente a proposta que recebeu e decida **item a item**, sem possuir conta interna — preservando evidências, idempotência, atomicidade e a regra de obsolescência da `DR-0001`.

## Escopo

- `workshop.public_quote_access`, `quote_decision_submission` e `quote_decision`, conforme o modelo aprovado (REVISION 2), com as FKs compostas.
- Token público opaco de 256 bits, gerado por `SecureRandom`, entregue uma única vez e persistido somente como digest SHA-256.
- `GET /api/public/quotes/{token}` e `POST /api/public/quotes/{token}/decisions`.
- Emissão, listagem e revogação do link pelo lado interno, exigindo `QUOTE_PRESENT`.
- Aprovação parcial, rejeição por item e permanência do que não foi decidido.
- Evidências: nome, CPF/CNPJ, aceite explícito, instante do servidor, IP e User-Agent.
- Idempotência por `(publicQuoteAccessId, requestId)` com digest do conteúdo canônico.
- Atomicidade: todos os itens validados antes de consolidar qualquer um.
- Resolução do `F-07-01`: serialização real entre `DECIDE` e `PRESENT`.
- Página pública no frontend e gestão do link na tela do orçamento.

## Fora do escopo

Retratação de decisão; edição ou remoção de decisão consolidada; reabertura de item rejeitado; eventos de domínio e outbox; efeito da aprovação sobre execução, estoque, compras ou financeiro; rate limiting no servidor de aplicação; validação de dígito verificador de CPF/CNPJ; mascaramento de documento em telas internas, que ainda não existem; notificação por WhatsApp ou e-mail; assinatura digital certificada.

## Regras aprovadas implementadas

| Regra | Origem | Onde |
| --- | --- | --- |
| Cliente decide sem conta interna | REQ `RN-14` | rota pública sem sessão e sem cookie |
| Aprovação parcial; ausência de decisão permanece pendente | REQ `RN-01` a `RN-03` | item omitido não gera registro |
| Decisão pertence à versão comercial exata | REQ `RN-11` | FK composta para `quote_revision_item` |
| Uma decisão efetiva por versão | REQ `RN-18` | `UNIQUE(quote_item_revision_id)` |
| Expiração bloqueia pendentes, não apaga decisões | REQ `RN-12`, `RN-13` | validade verificada na entrada; histórico intacto |
| Token válido não supera obsolescência | REQ `RN-23` | obsolescência reconferida dentro da transação |
| Rascunho não invalida; apresentação do mesmo item invalida | `DR-0001` opção B | `Quote.superseded` |
| Complemento reaproveitando a mesma versão não invalida | REQ `RN-22` | coberto por teste |
| Submissão idempotente | REQ `RN-16` | `(acesso, requestId)` + digest canônico |
| Submissão atômica | REQ `RN-17` | validação completa antes de qualquer gravação |
| Aceite explícito obrigatório | REQ seção 33 | invariante de domínio e `CHECK` no banco |
| Evidências preservadas | REQ seção 34 | `quote_decision_submission` |
| Tempo do servidor é autoridade | REQ seção 35 | `Clock` injetado |
| Token de alta entropia, opaco, nunca persistido em claro | Segurança seções 9 a 15 | `PublicAccessToken` |
| Token fora de logs, auditoria e erros | Segurança seção 27 | coberto por teste |
| `Cache-Control: no-store` e `Referrer-Policy: no-referrer` | Segurança seções 30 e 31 | cabeçalhos das respostas públicas |
| Minimização de dados públicos | Segurança seções 35 e 36 | projeção própria, sem custo, OS, cliente ou autor |
| Proibição de IDOR | Segurança seção 42 | token limitado a uma revisão; `revisionReference` conferido |
| Documento normalizado para dígitos | Segurança seção 45 | domínio da submissão |
| Sem invenção de política de dígito verificador | Segurança seção 46 | apenas comprimento é validado |

## Regras definidas nesta Task

1. `RN-T8-01` — Emitir o link exige `QUOTE_PRESENT`. **Justificativa:** o link é o meio pelo qual a proposta chega ao cliente; emiti-lo tem a mesma autoridade de apresentá-la. Não foi criada permissão nova para não alterar o IAM sem decisão.
2. `RN-T8-02` — O link só pode ser emitido para uma revisão **apresentada**, e sua validade nunca ultrapassa a validade comercial dela. Um link que sobrevivesse à proposta permitiria decidir sobre algo que já não está de pé.
3. `RN-T8-03` — Link inexistente, revogado e de outro orçamento respondem **exatamente igual** (`404 PUBLIC_QUOTE_NOT_AVAILABLE`). Distinguir os casos confirmaria a existência do recurso a quem não deveria sabê-la.
4. `RN-T8-04` — Link expirado responde `410 PUBLIC_QUOTE_EXPIRED` e não devolve o orçamento.
5. `RN-T8-05` — O IP registrado vem da conexão, nunca de cabeçalho enviado pelo cliente.
6. `RN-T8-06` — O mesmo `requestId` com conteúdo canônico idêntico é replay (`200`, `replayed = true`); com conteúdo diferente é conflito (`409 IDEMPOTENCY_KEY_REUSED`).
7. `RN-T8-07` — A forma canônica ordena as decisões, de modo que a mesma intenção enviada em ordem diferente continue sendo o mesmo envio.
8. `RN-T8-08` — O ponto de serialização entre decidir e apresentar é a linha do orçamento: as duas operações avançam `quote.version` na própria transação.

## Resolução do `F-07-01`

A decisão executa, **em uma única transação**:

```text
1. digest do token e carga do acesso
2. revogação e validade da credencial
3. correspondência entre revisionReference e o acesso
4. digest canônico e verificação de replay idempotente
5. validade comercial da apresentação
6. para cada item: pertence à revisão, ainda não decidido, não obsoleto
7. avanço condicional de quote.version  ← ponto de serialização
8. gravação da submissão e das decisões
```

O passo 7 é o que fecha a corrida. Apresentar também avança `quote.version`, então:

- se a apresentação commitar antes, a versão lida pela decisão já mudou e **nada é gravado**;
- se a apresentação estiver aberta, a atualização condicional da decisão **bloqueia** na linha travada e, ao liberar, encontra a versão nova e falha.

Não existe caminho em que uma decisão seja aceita usando uma obsolescência lida antes de uma apresentação concorrente. O teste `aConcurrentPresentationStopsADecisionBasedOnAStaleRead` reproduz exatamente esse entrelaçamento com duas transações reais.

## Critérios de aceite

1. `CA-01` — O token é devolvido uma única vez; o banco guarda apenas digest de 32 bytes e o token não aparece em nenhuma tabela nem na auditoria.
2. `CA-02` — Emitir e revogar exigem `QUOTE_PRESENT`.
3. `CA-03` — O `GET` público devolve apenas o necessário à decisão, com `no-store` e `no-referrer`, sem nenhum dado interno.
4. `CA-04` — Token inexistente e token revogado produzem resposta idêntica.
5. `CA-05` — Acesso expirado responde `410` e não permite decidir.
6. `CA-06` — Aprovação parcial: item aprovado, item rejeitado e item omitido coexistem, o omitido permanecendo pendente.
7. `CA-07` — Sem aceite explícito, ou sem nenhum item, a decisão é recusada e nada é gravado.
8. `CA-08` — Replay do mesmo envio responde `200 replayed = true` sem duplicar submissão ou decisão.
9. `CA-09` — Mesmo `requestId` com conteúdo diferente responde `409` e preserva a decisão original.
10. `CA-10` — Um item inválido invalida a submissão inteira; nada é gravado.
11. `CA-11` — Versão substituída por apresentação posterior responde `409 QUOTE_ITEM_REVISION_STALE`.
12. `CA-12` — Versão posterior apenas em rascunho **não** bloqueia a decisão.
13. `CA-13` — Complemento reaproveitando a mesma versão **não** bloqueia a decisão.
14. `CA-14` — Item já decidido responde `409 QUOTE_ITEM_ALREADY_DECIDED`.
15. `CA-15` — A submissão preserva nome, documento normalizado, aceite, instante do servidor, IP e User-Agent.
16. `CA-16` — Apresentação concorrente impede a decisão baseada em leitura vencida.
17. `CA-17` — O PostgreSQL rejeita decisão sobre item fora da revisão da submissão e segunda decisão para a mesma versão.
18. `CA-18` — O endpoint público funciona sem sessão e sem CSRF.
19. `CA-19` — A página pública não consulta nenhum endpoint interno e não envia credencial.
20. `CA-20` — `ApplicationModules.verify()`, `mvn test`, gates do frontend e `git diff --check` passam.

## Decision Requests

- `DR-0001` — `DECIDED`, opção B. Implementada e coberta por três testes opostos.
- `DR-0007` — `DECIDED`. Os valores apresentados ao cliente usam a política aprovada.
- `DR-0006` e `DR-0008` — `OPEN`, sem efeito nesta Task.

## Resultado dos gates

- `mvn test`: `94` testes, `0` failures, `0` errors, `0` skipped, `BUILD SUCCESS`.
- `ModularityTest` / `ApplicationModules.verify()`: `PASS`.
- `Task0008PublicQuoteDecisionIntegrationTest`: `18` testes `PASS` em PostgreSQL 18 real.
- Frontend: `72` testes `PASS`; TypeScript, build e lint verdes, `0` warnings.
- `git diff --check`: `PASS`.
- Regressão: IAM, CRM, catálogos, OS, itens físicos e orçamento interno `PASS`.

## Critérios de aceite

`20/20 IMPLEMENTADOS`.

## Revisão

- Revisão interna no papel do `AG-15`, com a lista de controles do `AG-09`: `APPROVED_WITH_NOTES`, em `docs/review/TASK-0008-revisao-tecnica.md`.
- `F-07-01` da TASK-0007: **RESOLVIDO** e provado por teste de concorrência com duas transações reais.
- Nenhum finding `CRITICAL`.
- **Dois findings `HIGH` são de implantação, não de código**, e devem bloquear a ida para produção: `F-08-02` (IP real atrás de proxy) e `F-08-03` (HTTPS/HSTS).
- `F-08-01` `MEDIUM` — rate limiting não implementado; deve ser resolvido antes de expor o endpoint na internet.
- `F-08-04` a `F-08-08` `MEDIUM`/`LOW`, aceitos com justificativa.
- Revisão externa independente: `PENDENTE`. Por ser a única superfície pública do sistema, esta Task **não** é declarada `DONE` sem ela.

## Bug encontrado e corrigido

`BUG-08-01` — o replay idempotente respondia `400`. A submissão anterior era reconstruída com lista de decisões vazia antes de carregá-las, e o próprio invariante do domínio derrubava a reconstrução. Na prática, um cliente que reenviasse por instabilidade de rede receberia erro em vez da confirmação de que sua decisão já estava registrada.

## Pendências declaradas para o proprietário e para o AG-14

1. `AG-14` — configurar `server.forward-headers-strategy` com proxies conhecidos antes de operar atrás de proxy reverso, sob pena de a evidência de IP registrar o proxy.
2. `AG-14` — HTTPS exclusivo e avaliação de HSTS no fluxo público; o token viaja no caminho da URL.
3. `AG-14` ou backend — rate limiting na superfície pública.
4. Proprietário — se emitir e revogar link devem ter permissão própria, em vez de reutilizar `QUOTE_PRESENT` (`F-08-05`).
5. Proprietário — política de validação de dígito verificador de CPF/CNPJ, junto com a regra de cadastro (`F-08-04`).

## Histórico

- 2026-09-15 — Task criada após o encerramento da TASK-0007, com `F-07-01` como pré-requisito declarado.
- 2026-09-15 — Backend, migration `V10`, superfície pública, página do cliente e testes implementados; `F-07-01` resolvido com serialização real; `BUG-08-01` corrigido; todos os gates verdes. Status movido para `REVIEW`, aguardando revisão externa independente e as pendências de implantação.
