# DR-0020 — Política de autenticação: bloqueio por tentativas, política de senha avançada e delegação de perfil Dono

## 1. Identificação

```text
ID: DR-0020
Título: Política de autenticação além do mínimo implementado na TASK-0019
Tipo: SECURITY
Status: OPEN
Criado em: 2026-10-05
Criado por: AG-09 — Segurança & Auditoria (auditoria de fechamento do MVP, TASK-0019)
Última atualização: 2026-10-05
```

## 2. Task relacionada

```text
Task principal: TASK-0019 — Fechamento do MVP/piloto para homologação
Outras tasks impactadas: TASK-0003 (IAM), TASK-0018 (administração de usuários)
Bloqueia a Task: NÃO para homologação; RECOMENDADA antes de exposição pública (produção)
```

## 3. Problema

A `TASK-0003` registrou explicitamente que a **política de bloqueio por tentativas de login não foi definida**
(`docs/architecture/security/TASK-0003-seguranca-iam.md`, ameaça "credential stuffing/brute force"). A auditoria
confirmou o comportamento: 30 logins errados seguidos continuam respondendo 401 e o login correto seguinte
funciona; nada limita tentativas no backend.

O que a `TASK-0019` entregou **sem inventar regra de negócio**:

- limite de requisições por IP no `/api/iam/auth/login` no nginx (6/min, rajada 20, resposta 429) — controle de
  infraestrutura, sem estado por conta;
- senha própria entre 8 e 72 bytes (o mesmo mínimo que o formulário já exigia; 72 é o limite técnico do bcrypt).

O que continua sem decisão:

1. **Bloqueio por conta** após N falhas (e por quanto tempo, quem desbloqueia). Risco inverso: o bloqueio por
   conta permite que um atacante trave o Dono de propósito (negação de serviço dirigida).
2. **Política de senha avançada**: composição, lista de senhas comuns, expiração, histórico. As diretrizes
   atuais (NIST SP 800-63B) desencorajam composição e expiração periódica.
3. **Quem pode criar usuário com perfil `DONO`.** Hoje qualquer usuário com `IAM_USERS_MANAGE` pode criar um
   usuário com `profileCode = DONO`; a `DR-0003` diz que os gerentes só recebem privilégio IAM "posteriormente,
   configurado pelo Dono". Se o Dono delegar `IAM_USERS_MANAGE` a um gerente (por perfil ou por exceção), esse
   gerente consegue criar um Dono e, por ele, escalar privilégio. O mesmo vale para `IAM_PROFILE_PERMISSIONS_MANAGE`
   (incluir/remover permissões do próprio perfil `DONO`) e `IAM_USER_EXCEPTIONS_MANAGE`.
4. **Segundo fator** (TOTP) para o Dono — não pedido pelos requisitos atuais.

## 4. Opções

### Item 1 — bloqueio por tentativas

- **A.** Atraso progressivo por conta+IP (sem bloqueio duro): reduz força bruta sem permitir trava dirigida.
- **B.** Bloqueio temporário por conta (ex.: 5 falhas → 15 min), com liberação automática e aviso ao Dono.
- **C.** Manter só o limite do nginx e monitorar `LOGIN_FAILED` na auditoria (estado atual).

### Item 3 — delegação de `DONO`

- **A.** Somente quem já é `DONO` pode criar usuário `DONO`, alterar o perfil `DONO` ou conceder `IAM_*` ao
  próprio perfil (regra de invariantes no backend).
- **B.** Manter o comportamento (a delegação a gerente é decisão consciente do Dono).

## 5. Recomendação

Item 1: **A** (atraso progressivo), por não criar vetor de negação de serviço. Item 2: manter o mínimo atual e
avaliar lista de senhas comuns; sem expiração periódica. Item 3: **A** — é um endurecimento sem custo funcional
(o Dono continua podendo tudo) e fecha o caminho de escalada. Item 4: fora do MVP.

## 6. Impactos

```text
Segurança: reduz força bruta e escalada de privilégio por delegação.
Dados: contador de falhas por conta (migration aditiva) apenas se a opção B/A do item 1 for escolhida.
API: novo código de erro para criação/alteração de DONO por não-Dono (403).
Testes: concorrência de tentativas, bloqueio/atraso, matriz de permissão de delegação.
```

## 7. Responsável e decisão

```text
Responsável pela decisão final: Proprietário do produto
Decisão final: PENDENTE
```

## 8. Mitigação vigente

Limite de requisições no nginx para o login; mínimo de 8 caracteres; a delegação de `IAM_*` a gerentes **não
deve ser concedida** no piloto (por padrão não é: só o perfil `DONO` possui `IAM_*`).

## 9. Resumo para a decisão do proprietário (atualizado em 2026-10-05, fase de produção)

Esta seção reúne, por item, o problema, o risco, as opções com o impacto de cada uma e a recomendação técnica. **Nada
aqui está decidido nem implementado**: a decisão é do proprietário e a `Decisão final` da seção 7 continua `PENDENTE`.

### 9.1 Bloqueio por tentativas de login (item 1)

```text
Situação hoje: nginx limita 6 tentativas/min por IP (rajada 20, resposta 429). O backend não conta falhas por conta.
Risco: força bruta LENTA (abaixo de 6/min) ou distribuída em vários IPs não é barrada; cada falha fica em
       iam.audit_event (LOGIN_FAILED), mas ninguém é avisado. O alvo mais valioso é a conta do Dono.
Risco inverso: bloqueio duro por conta deixa um atacante travar o Dono de propósito (negação de serviço dirigida).
```

| Opção | Segurança | Custo / impacto | Efeito para o usuário |
| --- | --- | --- | --- |
| **A.** Atraso progressivo por conta + IP (sem bloqueio duro) | reduz força bruta; sem trava dirigida | migration aditiva (contador), testes de concorrência | login fica mais lento após várias falhas |
| **B.** Bloqueio temporário (ex.: 5 falhas → 15 min) | mais forte contra força bruta; abre negação de serviço contra o Dono | migration, regra de desbloqueio, aviso ao Dono | conta travada por 15 min depois de erros (inclusive dos outros) |
| **C.** Manter só o nginx e monitorar `LOGIN_FAILED` | risco residual permanece | custo zero; alguém precisa olhar a auditoria | nenhum |

Recomendação técnica: **A**. Enquanto não houver decisão: senha do Dono forte (a primeira troca é obrigatória, mínimo 8
caracteres), nginx ativo e `LOGIN_FAILED` revisado com periodicidade combinada com a oficina.

### 9.2 Política de senha avançada (item 2)

| Opção | Impacto |
| --- | --- |
| **A.** Manter o mínimo atual (8 a 72 bytes) | custo zero; senhas como `12345678` ainda passam |
| **B.** A + recusa de senhas comuns (lista embarcada) | pequeno; sem migration; mensagem de erro nova |
| **C.** Composição obrigatória e expiração periódica | contraria as diretrizes atuais (NIST SP 800-63B); mais senhas anotadas e reaproveitadas |

Recomendação técnica: **B**, sem expiração periódica.

### 9.3 Quem pode criar, alterar ou delegar o perfil `DONO` (item 3)

```text
Situação hoje: só o perfil DONO possui as permissões IAM_* (padrão da migration). Quem tiver IAM_USERS_MANAGE
               consegue criar um usuário com profileCode = DONO. IAM_PROFILE_PERMISSIONS_MANAGE altera as permissões
               de qualquer perfil, inclusive DONO, e IAM_USER_EXCEPTIONS_MANAGE concede exceções individuais.
Risco: se o Dono delegar qualquer uma dessas permissões a um gerente, o gerente pode se promover a Dono
       (escalada de privilégio), e tudo o que ele fizer depois passa a valer como ação do Dono.
```

| Opção | Impacto |
| --- | --- |
| **A.** Só o `DONO` cria/altera usuário `DONO`, altera o perfil `DONO` ou concede `IAM_*` ao próprio perfil (invariante no backend, resposta 403) | endurecimento sem custo funcional (o Dono continua podendo tudo); novo código de erro e testes de matriz de permissão |
| **B.** Manter (a delegação a gerente é decisão consciente do Dono) | custo zero; o risco passa a ser aceito por escrito pelo proprietário |

Recomendação técnica: **A**.

**Controle vigente enquanto não houver decisão: NÃO delegue nenhuma permissão `IAM_*` a gerentes em produção.** Por
padrão ninguém além do Dono as tem, e `scripts/ops/preflight.sh` falha se encontrar `IAM_*` em perfil não-Dono ou em
exceção `ALLOW` de usuário ativo não-Dono (executado antes de liberar e a cada deploy). Com esse controle o item 3 não
bloqueia a produção; ele só deixa de valer se alguém delegar permissão IAM.

### 9.4 Segundo fator para o Dono (item 4)

Fora do MVP e não pedido pelos requisitos. Recomendação: não implementar agora; planejar TOTP para o Dono como Task
pós-MVP se a oficina quiser.

### 9.5 O que o proprietário precisa responder

1. Item 1: A, B ou C?
2. Item 2: A, B ou C?
3. Item 3: A (restringir) ou B (manter e aceitar o risco)?
4. Item 4: confirma que fica fora do MVP?

Efeito sobre a produção: o item 3 está coberto pelo controle operacional acima. Os itens 1 e 2 deixam um **risco
residual de força bruta lenta e de senha fraca** que só o proprietário pode aceitar (item 1 opção C; item 2 opção A)
ou mandar resolver (item 1 opção A ou B; item 2 opção B) antes da exposição pública.
