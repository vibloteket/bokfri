package org.fribok.bookkeeping.service.product;

import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Read operations for products, independent of the user interface. */
public final class ProductService {
    private final SSDB database;

    public ProductService(SSDB database) {
        this.database = database;
    }

    public List<SSProduct> list() {
        return database.getProducts().stream()
                .sorted(Comparator.comparing(SSProduct::getNumber,
                        Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    public Optional<SSProduct> find(String number) {
        return list().stream().filter(product -> number.equals(product.getNumber())).findFirst();
    }

    public ProductValidationResult validate(SSProduct product) {
        return ProductValidator.validate(product, database.getProducts());
    }

    public SSProduct create(SSProduct product) {
        ProductValidationResult validation = validate(product);
        if (!validation.valid()) {
            throw new ProductValidationException(validation);
        }
        database.addProduct(product);
        return product;
    }
}
