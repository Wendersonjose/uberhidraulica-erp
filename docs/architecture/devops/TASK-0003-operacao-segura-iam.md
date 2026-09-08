# TASK-0003 — Especificação operacional segura do IAM

## Parecer AG-14

```text
STATUS: DEVOPS_SPEC_APPROVED
IMPACTO: configuração segura, secrets, HTTPS e cookies
ALTERAÇÃO DE INFRAESTRUTURA NESTA FASE: NÃO
```

## Bootstrap e secrets

- dados do primeiro Dono vêm exclusivamente de variáveis de ambiente ou mecanismo de configuração secreta equivalente;
- senha inicial não terá default no `application.yml`, compose, código ou migration;
- arquivos `.env` reais não serão versionados;
- logs de startup não exibem valores de bootstrap;
- configuração ausente/inválida falha de modo explícito e sem persistência parcial;
- após existência de usuário, configuração de bootstrap não pode recriar ou sobrescrever o Dono.
- senha temporária criada para usuário ou reset existe somente em memória até a resposta imediata; não possui default de configuração e não aparece em log, métrica, trace ou auditoria.

Nomes exatos das variáveis são contrato operacional técnico da implementação e deverão ser documentados em exemplo sem valores reais.

## HTTPS e cookie

- produção requer HTTPS antes de ativar o fluxo autenticado;
- cookie de sessão: `HttpOnly` e `Secure` em produção;
- reverse proxy deve encaminhar headers confiáveis de forma controlada para o backend reconhecer conexão segura;
- `SameSite`, domínio e path serão configurados para a topologia aprovada, mantendo proteção CSRF;
- health endpoint não expõe usuário, sessão ou configuração sensível.

## Ambientes

DEV pode operar sem `Secure` apenas para desenvolvimento local sem TLS. HOMOLOG/PROD devem reproduzir controles de transporte relevantes. Dados e credenciais não são compartilhados entre ambientes.

## Observabilidade

- logs estruturados podem registrar correlation id e resultado, nunca senha, hash, cookie, CSRF token ou credencial temporária;
- falhas repetidas de login devem ser observáveis sem implementar política funcional de bloqueio não aprovada;
- métricas não usam e-mail como label;
- auditoria persistente não é substituída por logs.

## Validação antes de produção

- secret injection verificada;
- TLS e proxy verificados;
- flags do cookie verificadas no ambiente;
- timeout de oito horas verificado;
- sessão única verificada;
- redaction de logs verificada;
- ausência de senha inicial/temporária em observabilidade verificada;
- estratégia de backup/restauração inclui dados IAM e auditoria;
- rollback operacional da migration futura revisado.

## Decision Requests

Nenhuma aberta. Se a topologia futura exigir cookie cross-site ou entrega da credencial por canal externo, o impacto funcional e de segurança deverá ser reavaliado antes da mudança.
