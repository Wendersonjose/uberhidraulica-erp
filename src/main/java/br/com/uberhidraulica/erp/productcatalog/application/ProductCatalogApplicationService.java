package br.com.uberhidraulica.erp.productcatalog.application;

import br.com.uberhidraulica.erp.iam.CurrentUser;
import br.com.uberhidraulica.erp.iam.IamAuthorization;
import br.com.uberhidraulica.erp.productcatalog.ProductCatalogQuery;
import br.com.uberhidraulica.erp.productcatalog.domain.*;
import br.com.uberhidraulica.erp.productcatalog.port.ProductCatalogRepositoryPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProductCatalogApplicationService implements ProductCatalogQuery {
    public static final String COST_PERMISSION = "PRODUCT_COST_MANAGE";

    private final ProductCatalogRepositoryPort repository;
    private final CurrentUser currentUser;
    private final IamAuthorization authorization;

    public ProductCatalogApplicationService(ProductCatalogRepositoryPort repository, CurrentUser currentUser,
                                            IamAuthorization authorization) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.authorization = authorization;
    }

    @Transactional
    public CatalogProduct create(String description, String internalCode, String category, ProductType type,
                                 ProductUnit unit, BigDecimal referenceCost, BigDecimal salePrice,
                                 BigDecimal minimumStock) {
        CatalogProduct product = CatalogProduct.create(description, internalCode, category, type, unit,
                referenceCost, salePrice, minimumStock, Instant.now());
        return persist(product);
    }

    @Transactional(readOnly = true)
    public CatalogProduct get(UUID id) {
        return repository.findById(id).orElseThrow(ProductCatalogApplicationService::notFound);
    }

    @Transactional(readOnly = true)
    public List<CatalogProduct> list() { return repository.findAll(); }

    @Transactional
    public CatalogProduct update(UUID id, String description, String internalCode, String category, ProductType type,
                                 ProductUnit unit, BigDecimal referenceCost, BigDecimal salePrice,
                                 BigDecimal minimumStock, Boolean active) {
        CatalogProduct current = get(id);
        // Defesa em profundidade: PRODUCT_MANAGE cobre a edição de rotina, mas alterar custo/preço junto
        // com o resto do produto exige a permissão financeira separada, mesmo num único PUT.
        boolean costChanged = changed(current.referenceCost(), referenceCost) || changed(current.salePrice(), salePrice);
        if (costChanged && !authorization.hasPermission(currentUser.requireId(), COST_PERMISSION))
            throw new ProductCatalogException("PRODUCT_COST_MANAGE_REQUIRED", "Seu perfil não pode alterar custo ou preço de venda");
        return persist(current.update(description, internalCode, category, type, unit, referenceCost, salePrice,
                minimumStock, active, Instant.now()));
    }

    /** Compara por valor (não por escala), tratando ausência de custo/preço como um valor legítimo. */
    private static boolean changed(BigDecimal before, BigDecimal after) {
        if (before == null || after == null) return before != after;
        return before.compareTo(after) != 0;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductReference> product(UUID id) {
        return repository.findById(id).map(ProductCatalogApplicationService::reference);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductReference> products() { return repository.findAll().stream().map(ProductCatalogApplicationService::reference).toList(); }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductReference> productByGtin(String gtin) {
        return repository.findByGtin(gtin).map(ProductCatalogApplicationService::reference);
    }

    /**
     * Preenche GTIN/NCM só quando ainda não existem. Sobrescrever permitiria que um XML errado repontasse a
     * identidade fiscal de um produto em silêncio; corrigir GTIN gravado é operação de catálogo.
     */
    @Override
    @Transactional
    public boolean learnFiscalIdentity(UUID productId, String gtin, String ncm) {
        CatalogProduct current = get(productId);
        String newGtin = current.gtin() == null ? gtin : current.gtin();
        String newNcm = current.ncm() == null ? ncm : current.ncm();
        if (java.util.Objects.equals(newGtin, current.gtin()) && java.util.Objects.equals(newNcm, current.ncm())) {
            return false;
        }
        persist(new CatalogProduct(current.id(), current.description(), current.internalCode(), current.category(),
                current.type(), current.unit(), current.referenceCost(), current.salePrice(), current.minimumStock(),
                current.active(), current.createdAt(), Instant.now(), newGtin, newNcm));
        return true;
    }

    private static ProductReference reference(CatalogProduct p) {
        return new ProductReference(p.id(), p.description(), p.internalCode(), p.unit().name(), p.salePrice(),
                p.active(), p.category(), p.minimumStock(), p.gtin(), p.ncm());
    }

    /** A unicidade do código interno é garantida pelo índice do PostgreSQL, não por leitura prévia. */
    private CatalogProduct persist(CatalogProduct product) {
        try {
            return repository.save(product);
        } catch (DataIntegrityViolationException exception) {
            throw new ProductCatalogException("PRODUCT_INTERNAL_CODE_ALREADY_EXISTS", "Código interno já cadastrado");
        }
    }

    private static ProductCatalogException notFound() {
        return new ProductCatalogException("PRODUCT_NOT_FOUND", "Produto não encontrado");
    }
}
