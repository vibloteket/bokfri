package org.fribok.bookkeeping.service.voucher;

import se.swedsoft.bookkeeping.data.SSNewAccountingYear;
import se.swedsoft.bookkeeping.data.SSVoucher;
import se.swedsoft.bookkeeping.data.system.SSDB;

/** Voucher use cases shared by Swing and the command-line interface. */
public final class VoucherService {
    private final SSDB database;

    public VoucherService(SSDB database) {
        this.database = database;
    }

    public VoucherValidationResult validate(SSVoucher voucher) {
        return VoucherValidator.validate(voucher, database.getCurrentYear());
    }

    public SSVoucher create(SSVoucher voucher) {
        VoucherValidationResult validation = validate(voucher);
        if (!validation.valid()) {
            throw new VoucherValidationException(validation);
        }
        database.addVoucher(voucher, false);
        return voucher;
    }

    public int nextNumber() {
        return database.getLastVoucherNumber() + 1;
    }

    public SSNewAccountingYear currentYear() {
        return database.getCurrentYear();
    }
}
