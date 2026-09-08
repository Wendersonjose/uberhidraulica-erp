# DR-0003 — Complementos operacionais do IAM MVP

## 1. Identificação

```text
ID: DR-0003
Tipo: SECURITY
Status: DECIDED
Criado em: 2026-09-08
Criado por: AG-00 — Orquestrador
Última atualização: 2026-09-08
```

## 2. Task e origem

```text
Task principal: TASK-0003 — Fundação IAM
Módulo: IAM / Segurança e Auditoria
Origem da decisão: PROPRIETÁRIO DO PRODUTO
```

## 3. Problema e contexto

A `DR-0002` definiu bootstrap, permissões individuais, senhas, sessões e escopo. Permaneciam sem definição formal a credencial inicial de novos usuários, a capacidade administrativa inicial do primeiro Dono, a mutabilidade do catálogo de perfis e o ciclo `ACTIVE/INACTIVE`.

## 4. Motivo

- [x] regra de negócio ausente;
- [x] risco de segurança;
- [x] problema de persistência;
- [x] definição operacional.

## 5. Registro das alternativas

As decisões foram tomadas diretamente pelo proprietário do produto. Alternativas históricas não foram fornecidas e não serão inventadas para preencher o template.

```text
Opção escolhida: OUTRA — conjunto consolidado definido pelo proprietário
Necessita decisão do proprietário: SIM
Decisão já tomada: SIM
Task deve permanecer bloqueada pela DR: NÃO
```

## 6. Decisão final

### 6.1 Credencial inicial de novos usuários

- ao criar usuário interno, o sistema gera senha temporária com fonte criptograficamente segura;
- o valor em claro é exibido uma única vez no resultado imediato da criação;
- senha temporária em claro nunca é persistida, logada ou auditada;
- somente o hash é persistido;
- `mustChangePassword = true`;
- primeiro login usa o fluxo aprovado de troca obrigatória;
- recuperação automática por e-mail continua fora do MVP.

### 6.2 Permissões iniciais do primeiro Dono

- bootstrap garante o perfil fixo `DONO`;
- garante as permissões administrativas IAM necessárias ao próprio módulo;
- associa essas permissões ao perfil `DONO`;
- cria o primeiro Dono vinculado a esse perfil com `mustChangePassword = true`;
- `GERENTE_ADMINISTRATIVO` e `GERENTE_FINANCEIRO` não recebem automaticamente privilégios administrativos IAM;
- privilégios IAM desses gerentes somente podem ser configurados posteriormente pelo Dono autorizado.

### 6.3 Perfis fixos

Existem somente `DONO`, `GERENTE_ADMINISTRATIVO` e `GERENTE_FINANCEIRO`. Seus códigos são fixos e estáveis. Não é permitido criar ou excluir tipos, transformar um perfil em outro ou renomear códigos. Associações de permissões e exceções individuais continuam configuráveis. Perfil customizado não faz parte desta Task.

### 6.4 Estado de usuário

- estados: `ACTIVE` e `INACTIVE`;
- somente `ACTIVE` pode autenticar, atendidas as demais condições;
- inativação invalida imediatamente a sessão ativa;
- inativação não apaga usuário, histórico relevante de credencial ou auditoria;
- reativação não restaura sessão anterior e requer novo login;
- usuário não possui exclusão física;
- o último Dono `ACTIVE` não pode ser inativado;
- falha ao inativar o último Dono não produz alteração parcial;
- concorrência não pode resultar em zero Donos `ACTIVE`.

## 7. Impactos

```text
Funcional: criação/administração de usuários, perfis fixos e ciclo de estado.
Segurança: credencial temporária, autorização inicial, sessão e continuidade administrativa.
Arquitetura: catálogo fixo e casos de uso IAM.
Persistência: seeds idempotentes, estado e proteção concorrente do último Dono.
API: criação de usuário, remoção de criação de perfil e alteração de estado.
Infraestrutura: não exposição de segredo.
Financeiro, estoque, fiscal e integrações externas: nenhum.
```

## 8. Agentes envolvidos

AG-00, AG-01, AG-02, AG-09, AG-10, AG-11, AG-13, AG-14 e AG-15. AG-12 não participa porque frontend permanece fora do escopo.

## 9. Responsável e data

```text
Responsável pela decisão final: Proprietário do produto
Agente: PROPRIETÁRIO DO PRODUTO
Data: 2026-09-08
```

## 10. Requisitos, documentação e testes afetados

```text
Requisito: REQ-SEG-001
Task: TASK-0003
Migration necessária na implementação: SIM
Migration nesta fase: NÃO
ADR separada: NÃO
Handoff: SIM
```

Atualizar domínio, segurança, arquitetura, persistência, API, testes, DevOps, handoffs e revisão da `TASK-0003`.

## 11. Risco residual

Implementação incorreta da proteção do último Dono ou exposição de credencial temporária. Mitigação: invariantes transacionais, PostgreSQL/Testcontainers, testes concorrentes, revisão AG-09 e revisão independente AG-15.

## 12. Encerramento

```text
Status final: DECIDED
Bloqueio funcional removido: SIM
Task resultante: READY
Documentação da TASK-0003 atualizada: SIM
Decisão comunicada ao AG-00: SIM
```
