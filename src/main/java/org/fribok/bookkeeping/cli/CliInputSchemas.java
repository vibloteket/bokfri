package org.fribok.bookkeeping.cli;

import picocli.CommandLine.Command;

/** Schema subcommands for all JSON input contracts exposed by the CLI. */
final class CliInputSchemas {
    private CliInputSchemas() {}

    @Command(mixinStandardHelpOptions = true, name = "schema", description = "Print the generated company input JSON Schema")
    static final class Company extends JsonSchemaCommand { Company() { super("company", CompanyInput.class); } }

    @Command(mixinStandardHelpOptions = true, name = "schema", description = "Print the generated accounting-year input JSON Schema")
    static final class Year extends JsonSchemaCommand { Year() { super("year", AccountingYearInput.class); } }

    @Command(mixinStandardHelpOptions = true, name = "schema", description = "Print the generated opening-balance input JSON Schema")
    static final class OpeningBalance extends JsonSchemaCommand {
        OpeningBalance() { super("opening-balance", OpeningBalanceInput.class); }
    }

    @Command(mixinStandardHelpOptions = true, name = "schema", description = "Print the generated customer input JSON Schema")
    static final class Customer extends JsonSchemaCommand { Customer() { super("customer", CustomerInput.class); } }

    @Command(mixinStandardHelpOptions = true, name = "schema", description = "Print the generated product input JSON Schema")
    static final class Product extends JsonSchemaCommand { Product() { super("product", ProductInput.class); } }

    @Command(mixinStandardHelpOptions = true, name = "schema", description = "Print the generated supplier input JSON Schema")
    static final class Supplier extends JsonSchemaCommand { Supplier() { super("supplier", SupplierInput.class); } }

    @Command(mixinStandardHelpOptions = true, name = "schema", description = "Print the generated supplier-invoice input JSON Schema")
    static final class SupplierInvoice extends JsonSchemaCommand {
        SupplierInvoice() { super("supplier-invoice", SupplierInvoiceInput.class); }
    }

    @Command(mixinStandardHelpOptions = true, name = "schema", description = "Print the generated supplier-credit input JSON Schema")
    static final class SupplierCreditInvoice extends JsonSchemaCommand {
        SupplierCreditInvoice() { super("supplier-credit-invoice", SupplierCreditInvoiceInput.class); }
    }

    @Command(mixinStandardHelpOptions = true, name = "schema", description = "Print the generated invoice input JSON Schema")
    static final class Invoice extends JsonSchemaCommand { Invoice() { super("invoice", InvoiceInput.class); } }

    @Command(mixinStandardHelpOptions = true, name = "schema", description = "Print the generated credit-invoice input JSON Schema")
    static final class CreditInvoice extends JsonSchemaCommand {
        CreditInvoice() { super("credit-invoice", CreditInvoiceInput.class); }
    }

    @Command(mixinStandardHelpOptions = true, name = "schema", description = "Print the generated inpayment input JSON Schema")
    static final class Inpayment extends JsonSchemaCommand { Inpayment() { super("inpayment", InpaymentInput.class); } }

    @Command(mixinStandardHelpOptions = true, name = "schema", description = "Print the generated outpayment input JSON Schema")
    static final class Outpayment extends JsonSchemaCommand { Outpayment() { super("outpayment", OutpaymentInput.class); } }

    @Command(mixinStandardHelpOptions = true, name = "schema", description = "Print the generated voucher input JSON Schema")
    static final class Voucher extends JsonSchemaCommand { Voucher() { super("voucher", VoucherInput.class); } }
}
