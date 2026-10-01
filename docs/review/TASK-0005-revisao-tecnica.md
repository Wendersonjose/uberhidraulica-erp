# Revisão técnica interna — TASK-0005

- Revisor: `AG-15 — Revisor Técnico` (revisão interna da sprint autônoma)
- Data: `2026-09-14`
- Escopo revisado: módulo `productcatalog`, migration `V6`, contrato público, frontend de produtos e testes
- Resultado: `APPROVED_WITH_NOTES` — nenhum finding `CRITICAL` ou `HIGH` aberto

> Esta é uma revisão **interna**, feita pelo mesmo agente que implementou. Ela não substitui a revisão externa independente exigida pelo AGENTS.md antes do `DONE` definitivo.

## 1. Fronteira modular

`ApplicationModules.verify()` passa com o novo módulo. `productcatalog` declara `allowedDependencies = {}` e não referencia nenhum outro módulo.

`ProductEntity`, `ProductJpaRepository` e as classes de persistência são package-private. O único caminho de entrada entre módulos é `ProductCatalogQuery`. Nenhum repositório, entidade ou tabela do catálogo é acessível de fora.

Verificado: nenhum `import` de `br.com.uberhidraulica.erp.productcatalog.infrastructure` ou `.domain` fora do próprio módulo.

## 2. Regra de negócio inventada

Três pontos do escopo sugerido não estavam definidos por documento aprovado e foram resolvidos de forma conservadora e rastreável:

| Ponto | Resolução | Fonte |
| --- | --- | --- |
| Tipo funcional obrigatório | incluído, com os cinco tipos iniciais | AG-04 seção 10, documentação de domínio, que precede a Task na ordem de autoridade |
| Conjunto de unidades | fechado em `UNIDADE`, `LITRO`, `METRO`, `QUILOGRAMA` | AG-04 seções 21 e 22 |
| Precisão do estoque mínimo | três casas, provisória | `DR-0006`, registrada como `OPEN` |

O tipo funcional **não** constava da lista de campos sugerida na abertura da sprint. Foi incluído porque a documentação de domínio aprovada o exige e porque acrescentá-lo depois, como `NOT NULL`, obrigaria a inventar um valor para o catálogo existente. Está explicitamente registrado para conferência do proprietário.

## 3. Constraints e integridade

Todas as invariantes do domínio têm constraint correspondente em PostgreSQL, e todas são exercitadas por SQL direto, fora da aplicação, no teste de integração. Isso cobre o caso em que alguém escreve na tabela sem passar pela API.

O índice único do código interno é parcial e a normalização em maiúsculas ocorre no domínio, com `CHECK` que rejeita qualquer forma não canônica. Não há caminho para gravar `atf-d3` e `ATF-D3` como itens distintos.

## 4. Dinheiro

`BigDecimal` no Java e `NUMERIC(15,2)` no PostgreSQL. Nenhum `double` ou `float`. Nenhum valor monetário é derivado, somado ou arredondado neste módulo.

Ausência de valor é transportada como `null` até o frontend, que exibe `—`. Em nenhum ponto a ausência vira zero.

## 5. Histórico

Não existe exclusão. Não existe alteração retroativa: o catálogo descreve o presente, e os módulos consumidores guardam snapshot do que foi acordado, conforme AG-04 seções 38 e 87.

`createdAt` é preservado na atualização; apenas `updatedAt` é regravado.

## 6. Segurança

Nenhuma alteração no IAM. Os endpoints herdam `anyRequest().authenticated()` e o CSRF permanece ativo nas mutações — ambos verificados por teste, inclusive o `403` sem token.

Nenhuma permissão granular foi criada. Isso é deliberado: o modelo de permissões aprovado não define permissões de catálogo, e inventá-las alteraria o IAM sem decisão.

## 7. Findings

### `F-05-01` — `MEDIUM` — Tradução genérica de violação de integridade

`ProductCatalogApplicationService.persist` captura `DataIntegrityViolationException` e sempre reporta conflito de código interno. Se alguma outra constraint da tabela for violada, a mensagem será enganosa.

Mitigação atual: o domínio valida todas as demais invariantes antes de persistir, de modo que a única violação alcançável em operação normal é a do índice único. O padrão é idêntico ao já aprovado em `CrmApplicationService`.

Ação: aceito para esta Task; revisar se surgir uma segunda constraint alcançável.

### `F-05-02` — `LOW` — Ausência de controle otimista de versão

Atualizações concorrentes do mesmo produto seguem "o último a gravar vence", sem `@Version`.

Justificativa: o catálogo não guarda valor histórico — o histórico vive nos snapshots dos consumidores — e não há disputa por recurso escasso. Acrescentar `@Version` depois não quebra o contrato público.

Ação: aceito; revisar quando existir requisito de auditoria de alteração de preço.

### `F-05-03` — `LOW` — Listagem sem paginação nem busca

`GET /api/products` devolve o catálogo inteiro.

Justificativa: oficina única, catálogo de porte pequeno no MVP, e paginação sem requisito produziria contrato que precisa mudar quando o requisito real aparecer.

Ação: registrado como evolução futura.

## 8. Itens verificados sem finding

```text
violação de fronteira modular          nenhuma
falta de constraint                    nenhuma
falta de teste                         nenhuma no escopo declarado
inconsistência monetária               nenhuma
alteração retroativa                   nenhuma
autorização incorreta                  nenhuma
endpoint inseguro                      nenhum
dados duplicados                       nenhum
flag derivável armazenada              nenhuma
saldo de estoque no catálogo           ausente, conforme exigido
multi-tenancy                          ausente, conforme exigido
```

## 9. Conclusão

Apto para revisão externa. Não apto a `DONE` enquanto a suíte completa `mvn test` não for executada — ver a seção de bloqueios do relatório da sprint.
