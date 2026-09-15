# Revisão técnica e de segurança interna — TASK-0008

- Revisor: `AG-15 — Revisor Técnico`, com a lista de controles de `AG-09 — Segurança & Auditoria`
- Data: `2026-09-15`
- Escopo revisado: acesso público, decisão do cliente, migration `V10`, alteração do `SecurityConfig`, página pública e testes
- Resultado: `APPROVED_WITH_NOTES` — nenhum finding `CRITICAL`; um `HIGH` **de implantação**, fora do código

> Revisão **interna**, feita pelo mesmo agente que implementou. Não substitui revisão externa
> independente, que é especialmente desejável aqui por se tratar da única superfície pública do sistema.

## 1. Conferência contra a revisão de segurança aprovada

| Controle exigido | Situação |
| --- | --- |
| Token aleatório, opaco, imprevisível, alta entropia | `SecureRandom`, 256 bits, Base64 URL-safe |
| Proibição de `Random`, timestamp, ID sequencial, dado de negócio | cumprido; nada do negócio entra no token |
| Token bruto não persistido | cumprido; só o digest SHA-256, com teste |
| Digest apropriado para lookup | SHA-256 com índice único |
| Token fora de logs, auditoria, erros e eventos | cumprido; teste verifica ausência na auditoria |
| `Cache-Control: no-store` | cumprido em todas as respostas públicas, inclusive erro |
| `Referrer-Policy: no-referrer` | cumprido |
| Minimização de dados públicos | projeção própria; teste verifica ausência de campos internos |
| Proibição de IDOR | token limitado a uma revisão; `revisionReference` conferido contra o acesso |
| Identidade declarada preservada como evidência | cumprido |
| Documento normalizado para dígitos | cumprido |
| Sem inventar validação de dígito verificador | cumprido, deliberadamente |
| Aceite explícito obrigatório | invariante de domínio **e** `CHECK` no banco |
| Abrir o link não decide | `GET` é somente leitura |
| IP da conexão, não de cabeçalho do cliente | `getRemoteAddr()`; ver `F-08-02` |
| User-Agent como metadado, nunca autenticação | cumprido |
| Timestamp do servidor | `Clock` injetado |
| Idempotência no escopo do acesso | `(acesso, requestId)` + digest canônico |
| `requestId` não é autorização | cumprido; o token continua sendo a única credencial |
| HTTPS/HSTS em produção | responsabilidade de implantação; ver `F-08-03` |
| Rate limiting | **não implementado**; ver `F-08-01` |

## 2. A decisão de dispensar CSRF na rota pública

É a alteração mais sensível desta Task e merece ser explícita.

CSRF existe para impedir que um site hostil faça o navegador da vítima usar uma **credencial
ambiente** — tipicamente o cookie de sessão — sem que ela perceba. No fluxo público não existe
credencial ambiente: a autorização é um segredo de 256 bits no caminho da URL, que o navegador não
anexa sozinho.

Portanto:

- quem **não** tem o token não consegue nada forjando requisições;
- quem **tem** o token não precisa de vítima nenhuma — já pode decidir diretamente.

Exigir CSRF ali tornaria o fluxo impossível (o cliente não tem sessão de onde obter o token CSRF) sem
remover ataque algum. A dispensa é restrita a `/api/public/quotes/**`; nenhuma outra rota foi tocada,
e o serviço público não consulta o `SecurityContext` em ponto nenhum.

O filtro de troca obrigatória de senha também passou a ignorar a rota pública: um operador com senha
pendente que abra o link do cliente não deve ser barrado por uma regra que não se aplica ali.

## 3. Resolução do `F-07-01`

O finding aberto na TASK-0007 está **resolvido**. Apresentar e decidir passaram a avançar a mesma
linha — `quote.version` — dentro das respectivas transações. O efeito:

- apresentação commitada antes: a versão lida pela decisão já mudou, e nada é gravado;
- apresentação ainda aberta: a atualização condicional da decisão **bloqueia** na linha travada e, ao
  liberar, encontra a versão nova e falha.

Não sobra caminho em que uma decisão seja aceita com base em uma obsolescência lida antes de uma
apresentação concorrente.

O teste `aConcurrentPresentationStopsADecisionBasedOnAStaleRead` reproduz o entrelaçamento com duas
transações reais e latches, e não com simulação: a apresentação segura a linha enquanto a decisão já
leu o estado antigo.

## 4. Atomicidade e idempotência

Todos os itens são validados antes de qualquer gravação, e a submissão inteira roda em uma
transação. O teste com um item válido acompanhado de um item de **outro** orçamento confirma que
nada é gravado.

A idempotência usa digest do conteúdo canônico, com as decisões ordenadas: a mesma intenção enviada
em ordem diferente é replay, não conflito. A página pública gera um `requestId` por formulário, de
modo que reenviar depois de uma falha de rede repete o mesmo envio em vez de criar um segundo.

## 5. Defesa em profundidade no banco

Mesmo com bug no Java, o PostgreSQL recusa:

```text
decisão sobre item que não estava na revisão da submissão
submissão declarando revisão diferente da autorizada pelo acesso
segunda decisão para a mesma versão comercial
aceite falso
documento com comprimento incompatível com o tipo
digest de token com tamanho diferente de 32 bytes
```

Todos exercitados por SQL direto no teste, fora da aplicação.

## 6. Bug encontrado e corrigido

### `BUG-08-01` — replay idempotente respondia `400`

A submissão anterior era reconstruída a partir do banco **antes** de carregar suas decisões, com
lista vazia. O próprio invariante do domínio — "a submissão precisa conter ao menos uma decisão" —
derrubava a reconstrução, e todo replay virava erro de validação.

O efeito prático seria grave: um cliente que reenviasse por instabilidade de rede receberia erro em
vez da confirmação de que sua decisão já estava registrada. Corrigido lendo a linha crua primeiro e
construindo a submissão uma única vez, já com as decisões.

## 7. Findings

### `F-08-01` — `MEDIUM` — Sem rate limiting na superfície pública

`RATE_LIMIT_EXCEEDED` (`429`) consta da lista de erros aprovada e não foi implementado.

Avaliação do risco real: o token tem 256 bits, então adivinhação é inviável por força bruta. O que
resta é custo — tentativas repetidas consomem consulta ao banco — e ruído em log.

Ação: recomendado implementar no limite da infraestrutura (`AG-14`), que é onde limitação por IP
funciona melhor, ou no aplicativo se a implantação não oferecer esse recurso. **Não deve ir para
produção sem uma das duas.**

### `F-08-02` — `HIGH` de implantação — IP real atrás de proxy

O IP gravado vem de `getRemoteAddr()`. Atrás de um proxy reverso, isso registra o IP do proxy, e a
evidência da decisão perde valor.

Aceitar `X-Forwarded-For` cegamente seria pior — o cliente forjaria o próprio IP —, e por isso **não**
foi feito. A solução correta é de implantação: configurar `server.forward-headers-strategy` com
proxies conhecidos, conforme a seção 53 da revisão de segurança aprovada.

Ação: **requisito de implantação para `AG-14`**, registrado como bloqueio para produção atrás de
proxy. Em execução direta, o comportamento atual já está correto.

### `F-08-03` — `HIGH` de implantação — HTTPS e HSTS

A revisão aprovada exige HTTPS exclusivo no fluxo público e proíbe submissão por HTTP. Isso é
configuração de produção, não de código.

Ação: **requisito de implantação para `AG-14`**. O token viaja na URL; sem TLS, ele viaja em claro.

### `F-08-04` — `MEDIUM` — Documento sem validação de dígito verificador

Apenas o comprimento é conferido. É o comportamento **exigido** pela revisão de segurança aprovada,
que proíbe inventar política de rejeição aqui.

Consequência aceita: um documento estruturalmente válido mas matematicamente incorreto é aceito como
evidência. Ação: decidir junto com a regra de cadastro do cliente, não isoladamente.

### `F-08-05` — `LOW` — Emissão do link reutiliza `QUOTE_PRESENT`

Não foi criada permissão dedicada para emitir ou revogar link público, para não alterar o IAM sem
decisão do proprietário. Emitir tem a mesma autoridade de apresentar, então a reutilização é
defensável, mas revogar é um ato distinto que poderia ter permissão própria.

Ação: pergunta ao proprietário quando o quadro de permissões for revisto.

### `F-08-06` — `LOW` — Sem eventos nem outbox

A arquitetura aprovada prevê `QuoteItemApproved` e `QuoteItemRejected` com outbox. Não há consumidor
nenhum: execução, estoque e financeiro não existem.

Ação: aceito. Os eventos entram junto com o primeiro consumidor, e não antes.

### `F-08-07` — `LOW` — Decisão aprovada ainda não produz efeito operacional

Aprovar um item não inicia execução, não reserva estoque e não gera conta a receber. É a ordem
deliberada do roadmap, e a separação entre estado comercial e estado operacional é regra aprovada.

### `F-08-08` — `LOW` — Documento completo sem mascaramento

A revisão aprovada pede evitar exposição completa de CPF/CNPJ em telas internas. Nenhuma tela interna
exibe submissões hoje, então não há o que mascarar. Ação: tratar quando a tela de histórico de
decisões existir.

## 8. Itens verificados sem finding

```text
violação de fronteira modular        nenhuma; ModularityTest verde
token em log, auditoria ou erro      nenhum; coberto por teste
IDOR                                 coberto; revogado e inexistente respondem igual
dado interno na resposta pública     nenhum; coberto por teste
decisão sem aceite                   impossível no domínio e no banco
decisão duplicada                    impossível; UNIQUE por versão comercial
decisão em item de outra revisão     impossível; FK composta
retratação silenciosa                não existe operação de alteração ou remoção
arredondamento silencioso            nenhum; DR-0007 aplicada
regra de negócio inventada           nenhuma sem registro; oito regras declaradas como RN-T8-01 a 08
sessão usada no fluxo público        nenhuma, nem no backend nem na página
```

## 9. Conclusão

Apto para revisão externa. `F-08-02` e `F-08-03` são **requisitos de implantação e devem bloquear a
ida para produção**; `F-08-01` deve ser resolvido antes de expor o endpoint na internet.
