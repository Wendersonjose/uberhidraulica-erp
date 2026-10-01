# Contrato — Acesso público e decisão do cliente

- Origem: `TASK-0008`
- Base pública: `/api/public/quotes`
- Base interna: `/api/work-orders/{workOrderId}/quotes/{quoteId}`
- Status: `APPROVED` para o escopo da TASK-0008
- Data: `2026-09-15`

## 1. Superfície pública

```http
GET  /api/public/quotes/{token}
POST /api/public/quotes/{token}/decisions
```

**Sem sessão, sem cookie e sem CSRF.** A autorização é o token opaco do caminho, e ele alcança
exatamente uma apresentação de um orçamento. CSRF protege credencial ambiente do navegador; no fluxo
público não existe credencial ambiente, então exigi-lo tornaria o fluxo impossível sem remover
ataque nenhum — quem não tem o token não consegue nada, e quem tem não precisa de uma vítima.

Toda resposta pública leva `Cache-Control: no-store`, `Pragma: no-cache` e `Referrer-Policy: no-referrer`,
porque o token viaja no caminho da URL.

## 2. Token

```text
256 bits de SecureRandom, Base64 URL-safe sem padding
```

Entregue **uma única vez**, na resposta da emissão. O banco guarda apenas `SHA-256` do token, em
`BYTEA` de 32 bytes, com índice único. Não é derivado de nada do negócio: OS, placa, documento e
identificadores internos não servem como segredo, porque são conhecidos ou adivinháveis.

Perder o link significa emitir outro. Não existe caminho de recuperação, e isso é intencional.

## 3. `GET /api/public/quotes/{token}`

Somente leitura. **Abrir o link não aprova, não rejeita e não cria decisão.**

```json
{
  "revisionReference": "6fd9533c-…",
  "revisionNumber": 2,
  "presentedAt": "2026-09-14T12:00:00Z",
  "validUntil": "2026-09-21T12:00:00Z",
  "total": 700.00,
  "items": [
    { "itemReference": "252401d7-…", "description": "Reparo da caixa",
      "quantity": 1.0000, "unitPrice": 500.0000, "totalPrice": 500.00,
      "decisionStatus": "PENDING_APPROVAL", "decisionAvailability": "DECIDABLE" }
  ]
}
```

`validUntil` é o **menor** entre a validade comercial da apresentação e a validade da credencial.

Nada de interno atravessa: sem custo, margem, fornecedor, comissão, OS, veículo, cliente, autor ou
identificador de usuário. Há teste que verifica a ausência desses campos na resposta.

| `decisionStatus` | Significado |
| --- | --- |
| `PENDING_APPROVAL` | ainda sem decisão |
| `APPROVED` / `REJECTED` | decisão já consolidada |

| `decisionAvailability` | Significado |
| --- | --- |
| `DECIDABLE` | pode receber decisão agora |
| `ALREADY_DECIDED` | já tem decisão efetiva |
| `SUPERSEDED` | versão posterior do mesmo item foi apresentada |

## 4. `POST /api/public/quotes/{token}/decisions`

```json
{
  "revisionReference": "6fd9533c-…",
  "requestId": "4e4a0bd3-…",
  "customer": { "name": "José da Silva", "documentType": "CPF", "documentNumber": "000.000.000-00" },
  "explicitAcceptance": true,
  "decisions": [ { "itemReference": "252401d7-…", "decision": "APPROVE" } ]
}
```

`decision` aceita `APPROVE` e `REJECT`. **Item omitido não gera decisão** e permanece pendente:
ausência de decisão nunca é rejeição.

`explicitAcceptance` precisa ser `true`. É invariante de domínio e `CHECK` no banco.

O documento é normalizado para dígitos e conferido apenas no comprimento — 11 para CPF, 14 para CNPJ.
Dígito verificador **não** é validado: a revisão de segurança aprovada é explícita em não inventar
política de rejeição aqui, e recusar um documento válido impediria uma aprovação legítima.

Resposta `201` na primeira submissão, `200` em replay, ambas com o orçamento atualizado e
`replayed`.

### Sequência da transação

```text
1. digest do token e carga do acesso
2. revogação e validade da credencial
3. revisionReference confere com o acesso
4. digest canônico e verificação de replay
5. validade comercial da apresentação
6. por item: pertence à revisão, ainda não decidido, não obsoleto
7. avanço condicional de quote.version   ← ponto de serialização
8. submissão e decisões gravadas
```

O passo 6 é refeito **dentro** da transação, e o passo 7 garante que a apresentação concorrente não
consiga escorregar entre a validação e a gravação.

### Idempotência

Escopo `(publicQuoteAccessId, requestId)`, com digest do conteúdo canônico. A forma canônica ordena
as decisões, de modo que a mesma intenção enviada em ordem diferente continue sendo o mesmo envio.

Conhecer um `requestId` não concede acesso: a autorização continua sendo o token.

### Atomicidade

Todos os itens são validados antes de gravar qualquer um. Se um falhar, nenhum do mesmo envio é
consolidado.

## 5. Superfície interna

```http
POST /api/work-orders/{w}/quotes/{q}/revisions/{r}/public-access
GET  /api/work-orders/{w}/quotes/{q}/public-access
POST /api/work-orders/{w}/quotes/{q}/public-access/{a}/revoke
```

As três exigem `QUOTE_PRESENT`: o link é como a proposta chega ao cliente, então emiti-lo tem a mesma
autoridade de apresentá-la.

A emissão devolve `token` — a única vez que ele existe — e responde com `Cache-Control: no-store`.
A listagem **nunca** devolve o digest; o identificador do acesso é o que serve para correlacionar.

Só uma revisão apresentada e dentro da validade pode gerar link, e a validade da credencial nunca
ultrapassa a da proposta.

## 6. Erros

| Situação | HTTP | `code` |
| --- | --- | --- |
| Corpo inválido, sem item, aceite ausente, documento incoerente | `400` | `VALIDATION_ERROR` / `EXPLICIT_ACCEPTANCE_REQUIRED` |
| Token inexistente, revogado, ou item fora da revisão | `404` | `PUBLIC_QUOTE_NOT_AVAILABLE` / `QUOTE_ITEM_NOT_IN_REVISION` |
| `revisionReference` não corresponde ao link | `404` | `QUOTE_REVISION_NOT_AUTHORIZED` |
| Item já decidido | `409` | `QUOTE_ITEM_ALREADY_DECIDED` |
| Versão substituída por apresentação posterior | `409` | `QUOTE_ITEM_REVISION_STALE` |
| Mesmo `requestId` com outro conteúdo | `409` | `IDEMPOTENCY_KEY_REUSED` |
| Orçamento alterado durante o envio | `409` | `CONCURRENT_MODIFICATION` |
| Credencial ou proposta expirada | `410` | `PUBLIC_QUOTE_EXPIRED` |
| Sem `QUOTE_PRESENT` na emissão/revogação | `403` | `ACCESS_DENIED` |

Link inexistente e link revogado respondem **byte a byte igual**, com teste que compara as duas
respostas: distinguir os casos confirmaria a existência do recurso a quem não deveria sabê-la.

`RATE_LIMIT_EXCEEDED` (`429`) consta da lista aprovada e **não** foi implementado nesta Task —
registrado como finding `F-08-01`.

## 7. Ausências deliberadas

```text
retratação de decisão
edição ou remoção de decisão consolidada
reabertura de item rejeitado
rate limiting no servidor de aplicação
eventos de domínio e outbox
efeito da aprovação sobre execução, estoque, compras e financeiro
validação de dígito verificador
notificação ao cliente por WhatsApp ou e-mail
```
