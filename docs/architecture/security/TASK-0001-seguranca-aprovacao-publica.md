# Segurança & Auditoria — TASK-0001 — Aprovação Pública de Orçamento

## 1. Identificação

Task:

```text
TASK-0001
```

Requisito:

```text
REQ-ORC-001
```

Arquitetura relacionada:

```text
docs/architecture/oficina/TASK-0001-aprovacao-parcial-orcamento.md
```

Agente responsável:

```text
AG-09 — Segurança & Auditoria
```

Status:

```text
SECURITY_APPROVED
```

Módulo proprietário funcional:

```text
Oficina / Orçamento
```

Data:

```text
2026-09-08
```

---

# 2. Objetivo

Definir os controles de segurança necessários para permitir que um cliente externo consulte e decida itens de um orçamento sem possuir conta interna no ERP.

O fluxo precisa proteger:

```text
confidencialidade do orçamento;
integridade da decisão;
autenticidade técnica do acesso;
dados pessoais;
histórico;
evidências;
tokens;
auditoria.
```

---

# 3. Superfície pública

A arquitetura prevê conceitualmente:

```text
GET /api/public/quotes/{token}
```

e:

```text
POST /api/public/quotes/{token}/decisions
```

Os endpoints finais serão definidos pelo AG-11.

---

# 4. Princípio de segurança

Conhecer:

```text
quoteId
workOrderId
customerId
```

não é suficiente para acessar um orçamento.

O acesso público depende de:

```text
token criptograficamente imprevisível
+
escopo válido
+
validade
+
não revogação
```

---

# 5. Cliente público não é usuário IAM

O cliente não possui:

```text
User
Role
Password
Session interna
```

no MVP.

Portanto:

```text
PublicQuoteAccess
≠
User
```

---

# 6. Autoridade do acesso público

O acesso público representa uma autorização limitada.

Deve permitir somente operações explicitamente previstas para aquele orçamento/revisão.

---

# 7. Escopo mínimo

O token deve estar vinculado a:

```text
quoteId
quoteRevisionId
```

e não ser autorização genérica sobre toda a Ordem de Serviço.

---

# 8. Proibição de escalada

Um token válido para:

```text
Quote A
Revision 2
```

não pode acessar:

```text
Quote B
Revision 2
```

nem:

```text
Quote A
Revision 3
```

sem autorização específica.

---

# 9. Token

O token deve ser:

```text
aleatório;
criptograficamente seguro;
imprevisível;
opaco;
de alta entropia.
```

---

# 10. Fonte de aleatoriedade

A implementação deverá utilizar gerador criptograficamente seguro.

Em Java:

```text
SecureRandom
```

ou mecanismo criptográfico equivalente aprovado.

Não utilizar:

```text
Random
Math.random()
timestamp
ID sequencial
CPF
placa
número da OS
UUID previsível derivado de dados de negócio
```

como segredo de autorização.

---

# 11. Entropia

Recomendação mínima:

```text
256 bits de material aleatório
```

antes da codificação textual.

---

# 12. Codificação

O token poderá ser codificado usando formato URL-safe.

Exemplo técnico possível:

```text
Base64 URL-safe sem padding
```

A representação exata pertence ao AG-11.

---

# 13. Token bruto

O token bruto é segredo.

Depois de entregue ao cliente, não deve ser persistido em formato recuperável no banco como requisito normal.

---

# 14. Persistência do token

Persistir:

```text
tokenDigest
```

em vez do token bruto.

---

# 15. Digest recomendado

Como o token possui alta entropia aleatória, é aceitável utilizar digest criptográfico adequado para lookup.

Exemplo:

```text
SHA-256
```

sobre o token bruto.

---

# 16. Senha versus token

Não confundir token aleatório com senha humana.

Para senha de usuário:

```text
password hashing específico
```

como algoritmo adequado de password hashing.

Para token aleatório de alta entropia:

```text
digest criptográfico para lookup
```

é apropriado.

---

# 17. Índice

`tokenDigest` pode possuir índice único para lookup.

O token bruto não deve aparecer no índice.

---

# 18. Comparação

A implementação deve evitar operações inseguras que exponham o token através de logs ou mensagens de erro.

---

# 19. Ciclo de vida do acesso

`PublicQuoteAccess` deve possuir, conceitualmente:

```text
id
quoteId
quoteRevisionId
tokenDigest
createdAt
validUntil
revokedAt
```

---

# 20. Validade

O acesso deve respeitar a validade correspondente à apresentação comercial.

---

# 21. Token expirado

Quando:

```text
currentTime > validUntil
```

o token não pode autorizar nova decisão.

---

# 22. Decisão anterior

Expiração do token não altera decisões já registradas.

---

# 23. Revogação

Um acesso poderá ser explicitamente revogado.

Conceito:

```text
revokedAt != null
```

significa:

```text
acesso não autorizado
```

para novas operações.

---

# 24. Nova revisão

Quando uma nova revisão comercial for apresentada:

recomenda-se gerar novo acesso público vinculado à nova revisão.

---

# 25. Token antigo

Token antigo nunca pode autorizar automaticamente decisão sobre nova revisão.

---

# 26. Complemento

Nova revisão pode conter:

```text
itens previamente aprovados sem alteração
+
novos itens pendentes
```

A segurança do token não altera o histórico de aprovação desses itens.

---

# 27. Confidencialidade do token

O token não deve aparecer em:

```text
logs de aplicação;
logs de auditoria;
stack traces;
mensagens administrativas;
analytics;
eventos de domínio;
eventos de integração.
```

---

# 28. Redação de logs

Caso seja necessário correlacionar um acesso, utilizar:

```text
PublicQuoteAccessId
```

ou:

```text
parte não reversível/controlada do digest
```

Nunca o segredo bruto.

---

# 29. URL e logs de infraestrutura

Como o token poderá estar no path da URL:

reverse proxy e infraestrutura devem ser configurados para não registrar o path completo contendo o segredo quando isso expuser o token.

AG-14 deverá revisar isso antes de produção.

---

# 30. Referer

A página pública deve reduzir risco de vazamento do token através do cabeçalho `Referer`.

Recomendação:

```text
Referrer-Policy: no-referrer
```

ou política igualmente restritiva aprovada.

---

# 31. Cache

Dados do orçamento público não devem ser armazenados por caches compartilhados.

Recomendação:

```text
Cache-Control: no-store
```

---

# 32. HTTPS

Produção deve utilizar exclusivamente:

```text
HTTPS
```

para o fluxo público.

---

# 33. HTTP

Produção não deve permitir submissão da decisão por HTTP não criptografado.

---

# 34. HSTS

AG-14 deverá avaliar HSTS na configuração de produção HTTPS.

---

# 35. Dados públicos retornados

A resposta pública deve utilizar princípio de minimização.

Pode retornar somente dados necessários à decisão.

---

# 36. Dados que não devem ser expostos

Não retornar:

```text
custo da peça;
custo médio;
margem;
lucro;
comissão;
salário;
fornecedor;
dados bancários;
informações administrativas;
observações internas;
outros clientes;
outras OS;
permissões internas;
IDs internos desnecessários.
```

---

# 37. Referências públicas

Quando possível, não expor IDs internos sequenciais como mecanismo de navegação/autorização.

---

# 38. ID exposto não é segredo

Mesmo que um ID interno apareça por alguma necessidade:

segurança nunca deve depender de ele permanecer desconhecido.

---

# 39. Identificação do cliente

Para registrar decisão, o requisito exige:

```text
nome;
CPF ou CNPJ;
aceite explícito.
```

---

# 40. Snapshot da identidade declarada

Os dados informados devem ser preservados como evidência da decisão naquele momento.

Alteração posterior no cadastro do cliente não deve modificar esse registro histórico.

---

# 41. CPF/CNPJ

CPF/CNPJ é dado pessoal ou identificador de pessoa/empresa relevante.

Deve ter acesso restrito conforme necessidade operacional.

---

# 42. Exibição de CPF/CNPJ

Interfaces internas devem evitar exposição completa desnecessária.

Quando possível:

```text
mascarar na visualização
```

mantendo acesso completo somente quando necessário e autorizado.

---

# 43. Logs de CPF/CNPJ

Não registrar CPF/CNPJ completo em logs técnicos comuns.

---

# 44. Persistência do documento

A estratégia de persistência deve permitir auditoria e consulta autorizada.

Criptografia em nível de campo poderá ser avaliada na fase de implementação/infraestrutura conforme modelo de ameaça e recursos de produção.

Não bloquear TASK-0001 por isso.

---

# 45. Normalização

AG-11 deve normalizar o documento em formato consistente antes de persistência.

Exemplo:

```text
somente dígitos
```

quando tecnicamente aplicável.

---

# 46. Validação do documento

A existência de CPF/CNPJ informado é requisito aprovado.

A regra definitiva sobre validação matemática dos dígitos verificadores deverá seguir requisito funcional específico ou regra de cadastro já aprovada.

Não inventar política de rejeição nesta Task.

---

# 47. Nome

Nome informado deve possuir validação básica de formato e tamanho.

Não utilizar nome como segredo de autenticação.

---

# 48. Aceite explícito

A decisão somente pode ser consolidada quando:

```text
explicitAcceptance = true
```

---

# 49. Abertura do link

Apenas abrir o link:

```text
não é aprovação.
```

---

# 50. GET

O método de consulta pública deve ser:

```text
somente leitura
```

e não produzir decisão comercial.

---

# 51. POST

A decisão comercial exige operação explícita de escrita.

---

# 52. IP

Registrar IP da requisição como evidência técnica.

---

# 53. Proxy

Em produção, a obtenção do IP real deve confiar somente em proxies conhecidos/configurados.

Não aceitar cegamente qualquer:

```text
X-Forwarded-For
```

enviado diretamente pelo cliente.

---

# 54. User-Agent

Registrar User-Agent como metadado técnico.

Não utilizar User-Agent como mecanismo de autenticação.

---

# 55. Timestamp

Timestamp da decisão deve ser gerado pelo servidor.

Não confiar no horário enviado pelo browser.

---

# 56. Clock

Usar clock controlável/testável na aplicação.

---

# 57. Fonte de verdade temporal

Para a validade:

```text
tempo do servidor
```

é a autoridade.

---

# 58. Idempotência

O endpoint de decisão deve ser idempotente no contexto de uma submissão.

---

# 59. requestId

A arquitetura propôs:

```text
requestId
```

para identificar a submissão.

---

# 60. requestId não é autorização

Conhecer um `requestId` não concede acesso.

A autorização continua dependendo do token válido.

---

# 61. Escopo da idempotência

A unicidade deve considerar o contexto do acesso.

Exemplo conceitual:

```text
(publicQuoteAccessId, requestId)
```

---

# 62. Repetição legítima

Mesma chave + mesmo conteúdo:

```text
retorna resultado equivalente
sem novo efeito.
```

---

# 63. Reuso conflitante

Mesma chave + conteúdo diferente:

```text
rejeitar como conflito.
```

---

# 64. Digest da requisição

AG-11/AG-10 podem preservar hash canônico do conteúdo da submissão para detectar reuso conflitante.

---

# 65. Atomicidade

A submissão de múltiplas decisões é atômica conforme arquitetura.

Segurança deve preservar essa propriedade.

---

# 66. Payload adulterado

Se um item enviado não pertence à revisão autorizada:

```text
rejeitar a submissão.
```

---

# 67. Preço vindo do cliente

Ignorar como fonte de verdade qualquer:

```text
price
description
quantity
status
```

enviado pelo frontend além do contrato necessário.

---

# 68. Mass assignment

DTOs públicos devem declarar explicitamente os campos aceitos.

Não mapear automaticamente payload externo para entidade persistente.

---

# 69. Binding seguro

É proibido permitir que o cliente envie campos internos como:

```text
approvedAt
createdBy
quoteId
workOrderId
customerId
price
cost
status interno
```

e que estes sejam aceitos por binding automático.

---

# 70. Validação de item

Cada referência de item enviada deve ser resolvida dentro da revisão autorizada.

---

# 71. IDOR

A principal ameaça de autorização é:

```text
Insecure Direct Object Reference
```

O backend deve validar relação:

```text
token
→ PublicQuoteAccess
→ QuoteRevision
→ QuoteItemRevision
```

em toda operação.

---

# 72. Não confiar no frontend

Mesmo que frontend esconda itens:

backend deve validar o escopo novamente.

---

# 73. Enumeração

Respostas para token inexistente devem evitar indicar:

```text
"OS 123 existe mas você não tem acesso"
```

---

# 74. Erro de acesso

Preferir mensagem pública genérica.

Exemplo conceitual:

```text
Este link é inválido ou não está mais disponível.
```

---

# 75. Informação interna

Detalhes técnicos podem ir para logs seguros através de identificadores, não para resposta pública.

---

# 76. Rate limiting

Endpoints públicos devem possuir proteção contra abuso automatizado.

---

# 77. Escopo do rate limiting

Avaliar combinação de:

```text
IP;
PublicQuoteAccess;
endpoint.
```

---

# 78. Limites

Valores exatos de rate limit devem ser configuráveis por ambiente.

Não hardcode política definitiva nesta documentação.

---

# 79. Bloqueio absoluto por IP

Evitar política ingênua que bloqueie permanentemente clientes atrás de NAT.

---

# 80. Resposta a abuso

Pode utilizar:

```text
HTTP 429
```

conforme contrato final do AG-11.

---

# 81. CSRF

O fluxo público não deve depender de cookie autenticado como credencial de autorização do cliente.

Se a autorização pública utilizar exclusivamente token explícito e não credenciais ambientais, o risco clássico de CSRF é reduzido.

---

# 82. Sessão interna simultânea

Um funcionário autenticado que abra o link público continua sujeito às regras do acesso público naquele endpoint.

A sessão interna não deve expandir silenciosamente o escopo do token.

---

# 83. CORS

Se frontend e API estiverem em origens distintas:

configurar CORS explicitamente.

Não utilizar:

```text
Access-Control-Allow-Origin: *
```

com credenciais internas.

---

# 84. CSP

Frontend público deve utilizar Content Security Policy adequada quando implantado.

AG-12/AG-14 participam.

---

# 85. XSS

Todo conteúdo exibido deve ser escapado adequadamente.

Descrição de serviço vinda do banco não deve ser inserida como HTML arbitrário.

---

# 86. HTML

Não utilizar renderização de HTML não sanitizado proveniente de campos de negócio.

---

# 87. Clickjacking

Avaliar proteção por:

```text
frame-ancestors
```

via CSP.

A página de aprovação não precisa ser embutida em sites terceiros no requisito atual.

---

# 88. Content-Type

Respostas devem utilizar tipos de conteúdo corretos.

---

# 89. MIME sniffing

Recomenda-se:

```text
X-Content-Type-Options: nosniff
```

---

# 90. Headers

Conjunto de headers será finalizado por AG-14/AG-11.

---

# 91. Auditoria de domínio

A decisão deve preservar:

```text
QuoteDecisionSubmission
QuoteDecision
```

como histórico funcional.

---

# 92. Auditoria transversal

Além do histórico de domínio, registrar evento de auditoria para ações relevantes.

---

# 93. Evento auditável — apresentação

Registrar:

```text
orçamento/revisão apresentada;
usuário interno responsável;
timestamp;
acesso público criado;
```

---

# 94. Evento auditável — decisão pública

Registrar:

```text
PublicQuoteAccessId;
QuoteRevisionId;
QuoteDecisionSubmissionId;
timestamp;
IP;
user-agent;
resultado.
```

---

# 95. Não duplicar documento sensível

Auditoria transversal não precisa copiar CPF/CNPJ completo caso ele já esteja preservado no registro de domínio e possa ser referenciado.

---

# 96. Evento auditável — reabertura

Registrar:

```text
usuário interno;
item;
revisão anterior;
nova revisão;
timestamp.
```

---

# 97. Evento auditável — revogação

Registrar:

```text
PublicQuoteAccessId;
usuário responsável;
timestamp;
motivo quando aplicável.
```

---

# 98. Correlation ID

Cada requisição pública deve possuir identificador de correlação técnico.

---

# 99. Correlation ID não é segredo

Pode aparecer em logs e respostas de erro quando apropriado.

---

# 100. Tentativa inválida

Não registrar um evento de auditoria persistente pesado para cada varredura automatizada sem necessidade.

Usar observabilidade/rate limiting adequados.

---

# 101. Decisão efetiva

Toda decisão efetivamente aceita deve possuir evidência persistente.

---

# 102. Imutabilidade

Uma decisão consolidada não deve ser editável diretamente por CRUD administrativo.

---

# 103. Correção

Se futuramente for necessário corrigir uma decisão:

deverá existir operação de domínio/auditoria específica.

Não permitir:

```text
UPDATE quote_decision SET decision = ...
```

como fluxo normal.

---

# 104. Exclusão

Decisões e evidências não devem ser fisicamente apagadas como operação comum.

---

# 105. Banco

AG-10 deve proteger integridade com:

```text
FK;
UNIQUE;
NOT NULL;
constraints;
locking/versionamento.
```

---

# 106. Uma decisão por versão

No escopo atual:

```text
uma QuoteItemRevision
→ no máximo uma decisão efetiva.
```

---

# 107. Concorrência

A combinação recomendada permanece:

```text
constraint única
+
transação
+
optimistic locking quando necessário
+
idempotência
```

---

# 108. Approve/Approve simultâneo

Resultado:

```text
uma única decisão efetiva.
```

---

# 109. Approve/Reject simultâneo

Resultado:

```text
apenas uma decisão pode prevalecer.
```

A segunda operação deve receber conflito.

---

# 110. Revisão durante decisão

Decisão nunca deve migrar de revisão automaticamente.

---

# 111. Stale revision

O sistema deve possuir erro específico de domínio/API para revisão que não pode mais receber aquela decisão.

---

# 112. Falha depois do commit

Eventos destinados a outros módulos devem utilizar outbox conforme AG-02.

---

# 113. Dados pessoais no outbox

Evitar incluir:

```text
CPF/CNPJ;
nome completo;
IP;
user-agent
```

em eventos intermodulares quando consumidores não precisam deles.

---

# 114. Evento QuoteItemApproved

Consumidores precisam principalmente de referências técnicas do item/OS.

Não precisam da identidade completa do cliente.

---

# 115. Logs

Logs técnicos devem utilizar:

```text
quoteId;
quoteRevisionId;
publicQuoteAccessId;
submissionId;
correlationId;
eventId.
```

---

# 116. Logs proibidos

Não registrar:

```text
token bruto;
senha;
cookie;
CPF/CNPJ completo;
payload integral da decisão;
dados financeiros internos;
secrets.
```

---

# 117. Stack trace

Stack trace permanece em logs internos adequados.

Não retornar stack trace ao cliente.

---

# 118. Mensagens públicas

Mensagens não devem revelar:

```text
nome de tabela;
classe Java;
SQL;
stack trace;
tokenDigest;
IDs sensíveis desnecessários.
```

---

# 119. Monitoramento

Produção deve permitir monitorar:

```text
taxa de erros;
tentativas inválidas;
429;
falhas de decisão;
conflitos;
latência.
```

sem armazenar segredo.

---

# 120. TLS termination

Se TLS terminar em reverse proxy:

tráfego interno entre componentes deve respeitar arquitetura de confiança definida pelo AG-14.

---

# 121. Backup

Dados de decisão e evidência fazem parte de dados críticos de negócio.

Devem estar incluídos em política de backup do PostgreSQL.

---

# 122. Object Storage

TASK-0001 não exige documento binário para a decisão.

Não criar PDF apenas para segurança sem requisito.

---

# 123. Assinatura digital

A aprovação pública atual:

```text
não é assinatura digital com certificado ICP-Brasil.
```

Não apresentar como tal.

---

# 124. Evidência eletrônica

O sistema registra evidências técnicas da manifestação eletrônica conforme requisito.

A caracterização jurídica final não deve ser inventada pelo software.

---

# 125. Não prometer não repúdio absoluto

IP, User-Agent, CPF/CNPJ informado e token fornecem evidência técnica.

Não representam garantia criptográfica absoluta de identidade civil.

---

# 126. Segunda autorização do Dono

A aprovação do cliente não é uma das ações internas críticas que exigem segunda autorização do Dono.

---

# 127. Reabertura interna

Reabertura exige usuário interno autorizado.

Não exige automaticamente segunda autorização do Dono no requisito atual.

---

# 128. Permissão

AG-11 deverá implementar permissão específica ou equivalente para operações internas como:

```text
QUOTE_CREATE
QUOTE_REVISE
QUOTE_PRESENT
QUOTE_REOPEN
QUOTE_VIEW_HISTORY
```

A nomenclatura final pode ser refinada.

---

# 129. Página pública

A página pública não deve disponibilizar endpoints administrativos.

---

# 130. Sessão de funcionário

Não incluir informações administrativas extras no DTO apenas porque o browser possui sessão interna paralela.

---

# 131. Separação de controllers

Recomendação:

```text
PublicQuoteController
```

separado de:

```text
InternalQuoteController
```

para reduzir mistura acidental de políticas.

---

# 132. Spring Security

Rotas públicas devem ser explicitamente permitidas apenas onde necessário.

Não liberar genericamente:

```text
/api/public/**
```

sem revisão das rotas presentes.

---

# 133. PermitAll

`permitAll` significa apenas que não exige sessão IAM.

As regras de `PublicQuoteAccess` continuam obrigatórias dentro do caso de uso.

---

# 134. Method Security

Operações internas devem continuar verificando permissões no backend.

---

# 135. DTO público

Usar DTO específico.

Não reutilizar DTO administrativo completo.

---

# 136. DTO interno

Pode conter informações adicionais autorizadas, mas continua sujeito às permissões.

---

# 137. Desserialização

Configurar limites adequados de tamanho de payload.

---

# 138. Lista de decisões

Aplicação deve impor quantidade razoável de itens conforme orçamento real e evitar payload ilimitado.

O limite técnico exato será definido durante implementação.

---

# 139. Tamanho de strings

Nome, User-Agent e demais campos externos devem possuir limites de tamanho.

AG-10/AG-11 definirão valores coerentes.

---

# 140. User-Agent abusivo

Truncar/rejeitar valores acima do limite técnico definido.

Nunca permitir valor ilimitado no banco.

---

# 141. IP

Persistir em tipo/estrutura que suporte:

```text
IPv4
IPv6
```

AG-10 define tipo.

---

# 142. Documento

Não utilizar CPF/CNPJ como chave primária da decisão.

---

# 143. PublicQuoteAccessId

Utilizar identificador técnico próprio.

---

# 144. Token rotation

Nova apresentação pode gerar novo token.

Não reutilizar indefinidamente um segredo comprometido.

---

# 145. Revogação após suspeita

Deve ser possível revogar acesso sem apagar orçamento ou decisões passadas.

---

# 146. Falha de autenticação pública

Não incrementar estado comercial do orçamento.

---

# 147. Falha de validação

Não registrar decisão parcial.

---

# 148. Transação

Auditoria relacionada à decisão efetiva deve ser consistente com o resultado transacional.

---

# 149. Outbox e auditoria

Não emitir evento `QuoteItemApproved` se a transação da decisão sofreu rollback.

---

# 150. Reprocessamento

Retry técnico de uma requisição já consolidada deve encontrar a idempotência e não duplicar evento.

---

# 151. EventId

Eventos de integração devem possuir identificador único.

---

# 152. Consumidor idempotente

Módulos consumidores devem tratar reentrega futura sem duplicar efeitos.

---

# 153. Testes de segurança obrigatórios

Implementação futura deve testar:

```text
token válido;
token inexistente;
token alterado;
token expirado;
token revogado;
token de outra revisão;
item de outro orçamento;
item não pertencente à revisão;
payload com campo administrativo;
repetição idempotente;
mesma chave com payload diferente;
approve/approve concorrente;
approve/reject concorrente;
ausência de aceite;
ausência de identificação;
XSS em campos textuais;
enumeração;
logs sem token.
```

---

# 154. Teste S-01 — Token válido

Dado:

```text
token válido para Quote A / Revision 2
```

Resultado:

```text
somente Revision 2 autorizada é retornada.
```

---

# 155. Teste S-02 — Token inválido

Resultado:

```text
nenhum dado da OS é revelado.
```

---

# 156. Teste S-03 — Token de outra revisão

Dado:

```text
token R1
requisição tentando decidir R2
```

Resultado:

```text
negado.
```

---

# 157. Teste S-04 — IDOR

Dado:

```text
token de Quote A
itemReference de Quote B
```

Resultado:

```text
submissão rejeitada.
```

---

# 158. Teste S-05 — Expiração

Dado:

```text
token expirado
```

Resultado:

```text
nenhuma nova decisão consolidada.
```

---

# 159. Teste S-06 — Revogação

Dado:

```text
revokedAt preenchido
```

Resultado:

```text
acesso negado.
```

---

# 160. Teste S-07 — Ausência de aceite

Dado:

```text
explicitAcceptance = false
```

Resultado:

```text
nenhuma decisão consolidada.
```

---

# 161. Teste S-08 — Payload adulterado

Dado:

```text
price = 1.00
```

enviado pelo browser para item persistido como:

```text
R$ 500
```

Resultado:

```text
backend não utiliza R$ 1,00 como conteúdo aprovado.
```

---

# 162. Teste S-09 — Mass assignment

Cliente envia:

```text
status = APPROVED
approvedAt = ...
createdBy = ...
```

Resultado:

```text
campos ignorados/rejeitados conforme DTO;
nenhum privilégio obtido.
```

---

# 163. Teste S-10 — Reuso idempotente

Mesma chave e mesmo conteúdo:

```text
um efeito.
```

---

# 164. Teste S-11 — Reuso conflitante

Mesma chave e conteúdo diferente:

```text
conflito;
nenhuma segunda decisão.
```

---

# 165. Teste S-12 — Concorrência

```text
APPROVE
x
REJECT
```

simultâneos.

Resultado:

```text
uma única decisão efetiva.
```

---

# 166. Teste S-13 — XSS

Descrição contém texto malicioso.

Resultado:

```text
interface apresenta como conteúdo seguro;
script não executa.
```

---

# 167. Teste S-14 — Log

Após decisão:

log não contém:

```text
token bruto;
CPF/CNPJ completo.
```

---

# 168. Teste S-15 — Cache

Resposta pública deve possuir política que impeça cache compartilhado inadequado.

---

# 169. Ameaças principais

## T-01 — Roubo do link

Impacto:

```text
ALTO
```

Mitigações:

```text
HTTPS;
token forte;
expiração;
revogação;
no-referrer;
no-store.
```

---

# 170. T-02 — Enumeração

Impacto:

```text
ALTO
```

Mitigações:

```text
token imprevisível;
resposta genérica;
rate limiting.
```

---

# 171. T-03 — IDOR

Impacto:

```text
CRÍTICO
```

Mitigação:

```text
validação de escopo em cada item/revisão.
```

---

# 172. T-04 — Alteração do preço no navegador

Impacto:

```text
CRÍTICO
```

Mitigação:

```text
snapshot persistido é fonte de verdade.
```

---

# 173. T-05 — Requisição duplicada

Impacto:

```text
ALTO
```

Mitigação:

```text
idempotência.
```

---

# 174. T-06 — Corrida approve/reject

Impacto:

```text
ALTO
```

Mitigação:

```text
constraint;
transação;
controle de concorrência.
```

---

# 175. T-07 — Vazamento em logs

Impacto:

```text
ALTO
```

Mitigação:

```text
redação;
identificadores técnicos;
não logar token.
```

---

# 176. T-08 — XSS

Impacto:

```text
ALTO
```

Mitigação:

```text
escape;
CSP;
sem HTML arbitrário.
```

---

# 177. T-09 — Abuso automatizado

Impacto:

```text
MÉDIO/ALTO
```

Mitigação:

```text
rate limiting;
monitoramento.
```

---

# 178. T-10 — Proxy forjando IP

Impacto:

```text
MÉDIO
```

Mitigação:

```text
trusted proxies configurados.
```

---

# 179. Dados sensíveis consolidados

```text
token público bruto
CPF/CNPJ
nome
IP
user-agent
dados comerciais do orçamento
```

---

# 180. Classificação de segredo

Segredo crítico:

```text
token bruto
```

Dados pessoais/evidência:

```text
nome
CPF/CNPJ
IP
user-agent
```

Dados comerciais:

```text
descrição
quantidade
preço
```

---

# 181. Retenção

A política geral de retenção de dados pessoais ainda não foi definida nesta Task.

Não inventar exclusão automática.

---

# 182. LGPD

A implementação deverá respeitar as políticas legais e organizacionais aplicáveis.

Esta Task não define base legal, prazo de retenção ou procedimento jurídico completo de LGPD.

Esses pontos exigem decisão específica quando forem formalizados.

---

# 183. Decision Requests

Decision Requests impeditivas:

```text
NENHUMA
```

---

# 184. Pontos para refinamento futuro

Não bloqueiam a arquitetura:

```text
- política exata de rate limiting;
- criptografia em nível de campo para CPF/CNPJ;
- política formal de retenção;
- política global de CSP;
- limites exatos de tamanho de campos;
- tratamento de POST com decisions[] vazio.
```

---

# 185. Handoff AG-09 → AG-10

```text
Task:
TASK-0001

Status:
SECURITY_APPROVED

Persistência necessária:
- tokenDigest, nunca token bruto;
- PublicQuoteAccess revogável;
- validade;
- QuoteDecisionSubmission;
- evidência histórica;
- IP IPv4/IPv6;
- user-agent limitado;
- identidade declarada;
- idempotency requestId;
- constraints contra decisão duplicada.

Dados sensíveis:
- CPF/CNPJ;
- nome;
- IP;
- user-agent.

Índices:
- tokenDigest único;
- idempotência contextual.

Auditoria:
preservar referências,
evitar duplicação desnecessária de CPF/CNPJ.

Concorrência:
approve/approve
approve/reject

Resultado esperado:
modelo físico seguro e consistente.
```

---

# 186. Handoff AG-09 → AG-11

```text
Task:
TASK-0001

Status:
SECURITY_APPROVED

Requisitos obrigatórios:

TOKEN
- SecureRandom ou equivalente;
- alta entropia;
- token bruto entregue ao cliente;
- somente digest persistido;
- nunca logar token.

ACESSO
- escopo por QuoteRevision;
- expiração;
- revogação;
- IDOR protegido;
- resposta genérica para acesso inválido.

HTTP
- HTTPS em produção;
- Cache-Control no-store;
- Referrer-Policy restritiva;
- DTO público dedicado;
- sem mass assignment.

DECISÃO
- server timestamp;
- IP;
- user-agent;
- nome;
- CPF/CNPJ;
- aceite explícito;
- idempotência;
- atomicidade.

LOG
- sem token;
- sem CPF/CNPJ completo.

AUTORIZAÇÃO
- permitAll não substitui validação de PublicQuoteAccess.

CONCORRÊNCIA
- somente uma decisão efetiva por QuoteItemRevision.

Resultado:
contratos REST e casos de uso devem incorporar esses controles.
```

---

# 187. Handoff AG-09 → AG-12

```text
Task:
TASK-0001

Frontend público deve:

- não armazenar token persistentemente sem necessidade;
- não enviar preço como fonte de verdade;
- escapar conteúdo;
- não renderizar HTML arbitrário;
- deixar aceite explícito;
- diferenciar link inválido/expirado;
- não expor informações internas;
- não tratar abertura da página como aprovação.

Não implementar regra de autorização apenas no frontend.
```

---

# 188. Handoff AG-09 → AG-14

```text
Task:
TASK-0001

Antes de produção revisar:

- HTTPS;
- HSTS;
- reverse proxy;
- access logs sem token;
- Referrer-Policy;
- Cache-Control;
- CSP;
- rate limiting;
- trusted proxy headers;
- secrets;
- observabilidade.
```

---

# 189. Resultado do AG-09

```text
TASK:
TASK-0001

STATUS:
SECURITY_APPROVED

SUPERFÍCIE:
APROVAÇÃO PÚBLICA DE ORÇAMENTO

AUTENTICAÇÃO INTERNA:
NÃO EXIGIDA PARA CLIENTE

AUTORIZAÇÃO PÚBLICA:
PublicQuoteAccess

TOKEN:
CRIPTOGRAFICAMENTE ALEATÓRIO

TOKEN BRUTO NO BANCO:
NÃO

TOKEN DIGEST:
SIM

ESCOPO:
QUOTE REVISION ESPECÍFICA

EXPIRAÇÃO:
SIM

REVOGAÇÃO:
SIM

IDOR:
PROTEÇÃO OBRIGATÓRIA

DADOS PESSOAIS:
SIM

AUDITORIA:
OBRIGATÓRIA

IDEMPOTÊNCIA:
OBRIGATÓRIA

RATE LIMITING:
OBRIGATÓRIO EM PRODUÇÃO

HTTPS:
OBRIGATÓRIO EM PRODUÇÃO

DECISION REQUESTS IMPEDITIVAS:
NENHUMA

PRONTO PARA AG-10:
SIM

PRONTO PARA AG-11:
AGUARDAR AG-10
```

---

# 190. Definition of Done do AG-09

- [x] superfície pública analisada;
- [x] ameaça de IDOR analisada;
- [x] token definido;
- [x] armazenamento seguro do token definido;
- [x] escopo definido;
- [x] validade definida;
- [x] revogação definida;
- [x] dados pessoais identificados;
- [x] logs analisados;
- [x] evidência analisada;
- [x] idempotência analisada;
- [x] concorrência analisada;
- [x] XSS analisado;
- [x] cache analisado;
- [x] referrer analisado;
- [x] rate limiting identificado;
- [x] auditoria definida;
- [x] handoffs produzidos;
- [x] nenhuma Decision Request impeditiva.

---

# 191. Regra final

O link público funciona como uma credencial limitada.

Por isso:

```text
TOKEN
≠
ID
```

e:

```text
LINK CONHECIDO
=
ACESSO SOMENTE AO ESCOPO EXATAMENTE AUTORIZADO
```

Nunca:

```text
LINK CONHECIDO
=
ACESSO LIVRE À OS
```

A decisão deve permanecer vinculada a:

```text
TOKEN VÁLIDO
+
REVISÃO AUTORIZADA
+
ITEM AUTORIZADO
+
IDENTIFICAÇÃO
+
ACEITE EXPLÍCITO
+
EVIDÊNCIA
```

**SEGURANÇA DO LINK NÃO SUBSTITUI AS INVARIANTES DO DOMÍNIO, E AS INVARIANTES DO DOMÍNIO NÃO SUBSTITUEM A SEGURANÇA DO LINK.**
