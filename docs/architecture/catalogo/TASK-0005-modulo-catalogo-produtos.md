# Arquitetura — Módulo `productcatalog`

- Origem: `TASK-0005`
- Agente proprietário: `AG-02 — Arquitetura`
- Status: `APPROVED` para o escopo da TASK-0005
- Data: `2026-09-14`

## 1. Decisão de fronteira

O catálogo de produtos físicos é um **módulo próprio**, `productcatalog`, e não uma extensão de `servicecatalog`.

Motivo: serviço e produto físico têm ciclos de vida e dependências futuras diferentes. O produto passa a ser referenciado por Estoque, Compras e Fiscal; o serviço, por garantia e comissão. Unir os dois criaria um módulo com duas razões para mudar e forçaria Estoque a depender do catálogo de serviços.

```text
br.com.uberhidraulica.erp.productcatalog
├── ProductCatalogQuery          contrato público
├── package-info.java            @ApplicationModule(allowedDependencies = {})
├── api/                         REST + tradução de erro
├── application/                 caso de uso e transação
├── domain/                      invariantes
├── port/                        contrato de persistência
└── infrastructure/persistence/  adapter JPA (package-private)
```

`allowedDependencies = {}`: o catálogo de produtos não depende de nenhum outro módulo do domínio. Ele é folha do grafo.

## 2. Fluxo obrigatório

```text
ProductCatalogController
↓
ProductCatalogApplicationService
↓
CatalogProduct
↓
ProductCatalogRepositoryPort
↓
JpaProductCatalogRepositoryAdapter
```

`ProductJpaRepository` e `ProductEntity` são package-private. Nenhum módulo, nem o próprio controller, alcança JPA diretamente.

## 3. Contrato público

```java
public interface ProductCatalogQuery {
    Optional<ProductReference> product(UUID id);

    record ProductReference(UUID id, String description, String internalCode, String unit,
                            BigDecimal salePrice, boolean active) {}
}
```

Decisões do contrato:

1. **Consulta por identificador, não listagem.** Quem consome precisa referenciar um item escolhido, não navegar o catálogo alheio.
2. **`unit` como `String`.** O enum vive em `domain` e não deve atravessar a fronteira. O consumidor persiste a unidade como texto no seu próprio snapshot; ele não precisa reaplicar as regras do catálogo.
3. **`salePrice` pode ser nulo.** O contrato transporta a ausência em vez de mascará-la com zero. Cabe ao consumidor decidir se a ausência o impede de prosseguir — o catálogo não conhece a regra comercial de quem chama.
4. **Nenhum conceito de saldo.** O contrato não expõe, e nunca deve expor, disponibilidade. Consulta de disponibilidade será um contrato do módulo Estoque (AG-04, seção 119).
5. **`active` é informativo.** Bloquear o uso de item inativo é decisão de quem lança, não do catálogo.

Implementado por `ProductCatalogApplicationService`, seguindo o padrão já adotado por `ServiceCatalogQuery` e `CustomerVehicleQuery`.

## 4. Consumo previsto

```text
workorder  → referenciar e tirar snapshot de item físico lançado na OS
estoque    → identificar o item cujo saldo é controlado
compras    → identificar o item interno associado ao código do fornecedor
```

Nenhum desses módulos existe ou foi alterado nesta Task.

## 5. Eventos

Nenhum evento de domínio foi publicado nesta Task.

`ItemCatalogoCriado` e `ItemCatalogoInativado` estão previstos (AG-04, seção 117), mas não existe consumidor: publicar eventos sem assinante criaria infraestrutura sem função e um contrato difícil de alterar depois. Serão introduzidos quando Estoque ou Compras precisarem reagir.

## 6. Transações e concorrência

Escrita e leitura usam `@Transactional` no serviço de aplicação, com `readOnly = true` nas consultas.

Não há risco de concorrência relevante: o catálogo não disputa recurso escasso. A única corrida possível é a criação simultânea do mesmo código interno, resolvida pelo índice único parcial do PostgreSQL e traduzida para `409`, sem leitura prévia de verificação — que seria sujeita a janela de corrida.

Atualização concorrente do mesmo produto segue "o último a gravar vence". Isso é aceitável porque o catálogo não guarda valor histórico: o histórico vive nos snapshots dos módulos consumidores. Controle otimista de versão pode ser adicionado sem quebrar o contrato quando houver requisito de auditoria de alteração de preço.

## 7. Segurança

Nenhuma alteração no IAM. O `SecurityFilterChain` existente já exige autenticação para qualquer rota fora da lista pública, e CSRF permanece ativo para as mutações. Nenhuma permissão granular nova foi criada: o modelo de permissões vigente não define permissões de catálogo, e inventá-las alteraria o IAM aprovado.

## 8. O que foi deliberadamente não construído

```text
exclusão de produto
listagem paginada e busca por texto
importação em massa
histórico de alteração de preço
evento de domínio
permissão granular de catálogo
saldo em qualquer forma
```
