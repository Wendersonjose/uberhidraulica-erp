# TASK-0003 — Plano de testes do IAM

## Parecer AG-13

```text
STATUS: QA_PLAN_APPROVED
DECISÕES: DR-0002 e DR-0003 — DECIDED
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
- criação de usuário entrega temporária uma única vez e ativa `mustChangePassword`;
- catálogo aceita somente três perfis fixos e rejeita mutação de seus códigos;
- `INACTIVE` não autentica; inativação encerra sessão e reativação exige novo login;
- último Dono `ACTIVE` não é inativado.

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
- bootstrap cria/garante catálogo de perfis, permissões administrativas IAM e associações ao `DONO`;
- perfis `GERENTE_ADMINISTRATIVO` e `GERENTE_FINANCEIRO` não recebem privilégios IAM automaticamente.

## Usuários, perfis e credencial inicial

- criação autorizada gera temporária criptograficamente segura e a retorna apenas na resposta imediata;
- leitura posterior nunca retorna temporária;
- banco, logs e auditoria não contêm o valor em claro;
- usuário novo fica `ACTIVE` e com `mustChangePassword = true`;
- catálogo contém exatamente três códigos fixos;
- rota de criação/exclusão/rename de tipo de perfil não existe;
- associação perfil-permissão e exceção individual continuam configuráveis;
- não existe endpoint de exclusão física de usuário.

## Estado e continuidade administrativa

- usuário `INACTIVE` não autentica mesmo com senha correta;
- inativação de usuário autenticado invalida sessão imediatamente;
- reativação preserva dados, não restaura sessão e exige login;
- tentativa de inativar o único Dono `ACTIVE` falha atomicamente;
- duas inativações concorrentes de Donos não deixam zero Donos `ACTIVE`;
- histórico de usuário, credencial relevante e auditoria permanece após inativação.

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
- concorrência da proteção do último Dono `ACTIVE`;
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
evidência da matriz de autorização, perfis fixos e proteção do último Dono;
AG-13 = QA_APPROVED;
AG-15 = APPROVED.
```
