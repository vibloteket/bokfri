package org.fribok.bookkeeping.service.supplier;

import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Supplier use cases shared by Swing and CLI. */
public final class SupplierService {
    private final SSDB database;

    public SupplierService(SSDB database) { this.database = database; }

    public List<SSSupplier> list() {
        return database.getSuppliers().stream()
                .sorted(Comparator.comparing(SSSupplier::getNumber,
                        Comparator.nullsLast(String::compareTo))).toList();
    }

    public Optional<SSSupplier> find(String number) {
        return list().stream().filter(item -> number.equals(item.getNumber())).findFirst();
    }

    public int nextOutpaymentNumber() {
        return list().stream().map(SSSupplier::getOutpaymentNumber)
                .filter(java.util.Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
    }

    public SupplierValidationResult validate(SSSupplier supplier) {
        return SupplierValidator.validate(supplier, database.getSuppliers());
    }

    public SSSupplier create(SSSupplier supplier) {
        if (supplier.getOutpaymentNumber() == null) {
            supplier.setOutpaymentNumber(nextOutpaymentNumber());
        }
        SupplierValidationResult validation = validate(supplier);
        if (!validation.valid()) { throw new SupplierValidationException(validation); }
        database.addSupplier(supplier);
        return supplier;
    }
}
