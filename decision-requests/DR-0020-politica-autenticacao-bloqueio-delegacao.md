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
