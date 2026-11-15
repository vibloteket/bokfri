-- Widen supplier voucher edit timestamps without changing stored values or graph relationships.
-- Default TIMESTAMP precision was six fractional digits; domain LocalDateTime supports nine.
ALTER TABLE supplier_invoice_voucher_row ALTER COLUMN edited_at TIMESTAMP(9) WITH TIME ZONE;
ALTER TABLE supplier_credit_invoice_voucher_row ALTER COLUMN edited_at TIMESTAMP(9) WITH TIME ZONE;
