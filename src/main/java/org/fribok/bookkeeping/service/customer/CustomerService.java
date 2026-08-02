package org.fribok.bookkeeping.service.customer;

import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.system.SSDB;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Read operations for customers, independent of the user interface. */
public final class CustomerService {
    private final SSDB database;

    public CustomerService(SSDB database) {
        this.database = database;
    }

    public List<SSCustomer> list() {
        return database.getCustomers().stream()
                .sorted(Comparator.comparing(SSCustomer::getNumber,
                        Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    public Optional<SSCustomer> find(String number) {
        return list().stream().filter(customer -> number.equals(customer.getNumber())).findFirst();
    }
}
