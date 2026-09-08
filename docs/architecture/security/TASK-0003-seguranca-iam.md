# TASK-0003 — Especificação de segurança e auditoria IAM

## Parecer AG-09

```text
STATUS: SECURITY_APPROVED
AUTENTICAÇÃO: e-mail + senha; Spring Security Session
AUTORIZAÇÃO: perfil com exceção individual prevalente
DECISION REQUESTS ABERTAS: 0
PRONTO: SIM
```

## Controles obrigatórios

- hash de senha adaptativo provido por biblioteca aprovada; parâmetros escolhidos por benchmark/recomendação vigente na implementação;
- comparação de credenciais pelo componente aprovado, sem código criptográfico próprio;
- senha inicial e temporária recebidas/geradas somente em memória pelo tempo indispensável;
- uma única exposição da credencial temporária ao Dono que executou reset autorizado;
- exclusão de senha, hash, cookie e token CSRF de logs, auditoria e respostas de leitura;
- proteção contra enumeração de contas;
- proteção CSRF habilitada em operações mutáveis autenticadas;
- renovação do identificador de sessão no login e invalidação no logout;
- máximo de uma sessão por usuário e timeout inativo de oito horas;
- `HttpOnly` sempre e `Secure` em produção;
- autorização por permissão efetiva aplicada no backend;
- acesso restrito durante troca obrigatória.

## SameSite e HTTPS

`SameSite` é configuração técnica a ser escolhida conforme topologia real do cliente/backend, preservando proteção CSRF e funcionamento legítimo. Produção exige HTTPS antes de emitir cookie `Secure`; terminação TLS e encaminhamento seguro de headers devem ser validados pelo AG-14. Nenhuma configuração local pode reduzir silenciosamente o controle de produção.

## Política técnica de senha

Esta Task não cria regra funcional de composição não aprovada. O mecanismo deve aceitar a senha sem truncamento silencioso, impor limites técnicos contra abuso e usar encoder adaptativo. Se a implementação pretender exigir composição, tamanho mínimo operacional ou expiração periódica que altere a experiência, deverá abrir Decision Request antes de fazê-lo.

## Auditoria

Auditoria é persistente, separada de logs e não guarda segredo. Para mudanças administrativas registra ator, alvo, ação, instante, resultado e antes/depois não sensível. Para login falho preserva evidência minimizada e correlation id sem confirmar conta. Ações de senha registram apenas que ocorreu mudança/reset e o resultado.

## Ameaças mínimas para testes/revisão

```text
enumeração de usuário;
credential stuffing/brute force (observável; política de bloqueio não definida nesta Task);
session fixation;
session hijacking;
CSRF;
bypass de autorização;
precedência errada de exceção;
bootstrap duplicado;
segredo em logs/respostas;
reuso de sessão invalidada;
acesso amplo durante troca obrigatória.
```

Bloqueio/limitação de tentativas não foi aprovado funcionalmente e não deve ser inventado. A implementação deve permitir observabilidade e futura evolução sem fingir que esse controle já existe.
