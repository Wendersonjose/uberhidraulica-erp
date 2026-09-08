# TASK-0003 — Plano de testes do IAM

## Parecer AG-13

```text
STATUS: QA_PLAN_APPROVED
IMPLEMENTAÇÃO TESTADA NESTA FASE: NÃO
PRONTO PARA IMPLEMENTAÇÃO: SIM
```

Todos os testes futuros devem referenciar `TASK-0003`, `REQ-SEG-001` e os critérios `CA-IAM-*` correspondentes.

## Domínio e aplicação

- matriz completa: perfil permite/nega × `INHERIT`, `ALLOW`, `DENY`;
- ausência de exceção equivale à herança, sem alterar precedência;
- troca obrigatória limita casos de uso e é removida após troca bem-sucedida;
- usuário autenticado altera própria senha;
- não Dono e usuário sem permissão não redefinem senha alheia;
- Dono autorizado redefine, ativa troca obrigatória e recebe credencial uma vez;
- credencial anterior deixa de autenticar após troca/reset;
- auditoria acompanha mudanças relevantes sem segredo.

## Autenticação e API

- login válido cria sessão;
- senha/e-mail inválidos não criam sessão e não enumeram conta;
- logout invalida sessão;
- sessão atual retorna identidade/permissões efetivas sem dados secretos;
- rota protegida sem sessão é negada;
- rota administrativa sem permissão é negada no backend;
- requests/responses nunca expõem hash;
- validação e envelope de erro consistentes.

## Sessões

- primeiro login cria uma sessão;
- segundo login válido do mesmo usuário invalida a anterior;
- concorrência de logins preserva no máximo uma sessão efetiva;
- oito horas sem atividade expiram a sessão;
- atividade antes do limite renova o tempo de inatividade;
- logout não afeta indevidamente sessão de outro usuário;
- identificador é renovado no login para mitigar fixation;
- cookie `HttpOnly`; em perfil de produção também `Secure`.

Testes de tempo devem usar relógio/infraestrutura controlável quando viável, sem espera real de oito horas.

## Bootstrap

- banco vazio + configuração válida cria um Dono;
- usuário existente torna bootstrap no-op sem alteração;
- configuração ausente/inválida não cria registro parcial;
- senha não consta em migration, código, logs ou auditoria;
- execução repetida é idempotente quanto à criação;
- duas execuções concorrentes criam no máximo um primeiro Dono;
- bootstrap ativa troca obrigatória.

## Persistência PostgreSQL/Testcontainers

- migration futura inicia em PostgreSQL real;
- unicidade de e-mail normalizado;
- FKs e nulabilidade;
- unicidade perfil/permissão e usuário/permissão;
- valores válidos de resolução;
- uma credencial vigente por usuário;
- sessão por principal/expiração;
- transações de senha/permissão/auditoria;
- concorrência do bootstrap;
- não usar H2 como substituto.

## Segurança

- CSRF ausente/inválido rejeita mutação e CSRF válido permite fluxo autorizado;
- cookie e flags por ambiente;
- tentativa de session fixation;
- reutilização de sessão invalidada;
- bypass por chamada direta de endpoint;
- mass assignment em administração;
- enumeração por resposta de login;
- senha/hash/temporária/cookie ausentes de logs e auditoria;
- entrada excessiva recebe limite técnico seguro;
- serialização de erros não inclui stack trace ou segredo.

## Auditoria

Validar sucesso/falha conforme especificado para bootstrap, login, logout, troca/reset, usuário, perfil, permissão e exceção. Conferir ator quando existente, alvo, instante, resultado, correlation id e ausência de segredo.

## Regressão arquitetural

- `ApplicationModules.verify()` continua passando;
- módulo externo não acessa entity/repository/tabela IAM;
- Controller não acessa JPA diretamente;
- entidades não aparecem no contrato REST.
- domínio, tabelas, endpoints e autenticação IAM não contêm tenant nem `tenant_id`.

## Quality gate futuro

```text
0 falhas;
0 testes ignorados sem justificativa;
PostgreSQL/Testcontainers executado;
testes de segurança críticos executados;
evidência da matriz de autorização;
AG-13 = QA_APPROVED;
AG-15 = APPROVED.
```
