package se.swedsoft.bookkeeping.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.common.SSInvoiceType;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SSInvoice}.
 *
 * Only methods that do not require a live SSDB connection are tested here.
 * The default constructor is safe: it calls {@code new SSVoucher()} which
 * calls SSDB-backed {@code SSVoucherMath.getMaxNumber()}, but that path is
 * guarded and returns 0 when no accounting year is open.
 * {@code generateVoucher()} and {@code doAutoIncrecement()} are NOT tested
 * here because they require a fully initialised database.
 */
class SSInvoiceTest {

    private SSInvoice invoice;

    @BeforeEach
    void setUp() {
        invoice = new SSInvoice();
    }

    // ---- Default constructor defaults ----

    @Test
    void defaultTypeIsNormal() {
        assertThat(invoice.getType()).isEqualTo(SSInvoiceType.NORMAL);
    }

    @Test
    void defaultCurrencyRateIsOne() {
        assertThat(invoice.getCurrencyRate()).isEqualByComparingTo("1");
    }

    @Test
    void defaultEnteredIsFalse() {
        assertThat(invoice.isEntered()).isFalse();
    }

    @Test
    void defaultInterestInvoicedIsFalse() {
        assertThat(invoice.isInterestInvoiced()).isFalse();
    }

    @Test
    void defaultNumRemindersIsZero() {
        assertThat(invoice.getNumReminders()).isEqualTo(0);
    }

    @Test
    void defaultStockInfluencingIsTrue() {
        assertThat(invoice.isStockInfluencing()).isTrue();
    }

    @Test
    void defaultOcrNumberIsNull() {
        assertThat(invoice.getOCRNumber()).isNull();
    }

    // ---- getType null guard ----

    @Test
    void getTypeReturnsNormalWhenTypeWasSetToNull() {
        invoice.setType(null);

        assertThat(invoice.getType()).isEqualTo(SSInvoiceType.NORMAL);
    }

    // ---- setType / getType ----

    @Test
    void setTypeUpdatesType() {
        invoice.setType(SSInvoiceType.CASH);

        assertThat(invoice.getType()).isEqualTo(SSInvoiceType.CASH);
    }

    // ---- setCurrencyRate / getCurrencyRate ----

    @Test
    void setCurrencyRateUpdatesRate() {
        invoice.setCurrencyRate(new BigDecimal("8.75"));

        assertThat(invoice.getCurrencyRate()).isEqualByComparingTo("8.75");
    }

    // ---- setLocalDueDate / getLocalDueDate ----

    @Test
    void setLocalDueDateStoresDate() {
        LocalDate d = LocalDate.of(1970, 1, 2);
        invoice.setLocalDueDate(d);

        assertThat(invoice.getLocalDueDate()).isEqualTo(d);
    }

    // ---- setDueDate() no-arg: no payment term → falls back to iDate ----

    @Test
    void setDueDateNoArgWithNoPaymentTermSetsDueDateToInvoiceDate() {
        // Use a day-boundary Date since fields are now LocalDate internally
        LocalDate invoiceDate = LocalDate.of(2024, 7, 20);
        invoice.setLocalDate(invoiceDate);
        // Tests may share a JVM with database fixtures that install a current company.
        invoice.setPaymentTerm(null);
        invoice.setDueDate();

        assertThat(invoice.getLocalDueDate()).isEqualTo(invoiceDate);
    }

    // ---- setYourOrderNumber / getYourOrderNumber ----

    @Test
    void setYourOrderNumberUpdatesValue() {
        invoice.setYourOrderNumber("PO-12345");

        assertThat(invoice.getYourOrderNumber()).isEqualTo("PO-12345");
    }

    // ---- setVoucher / getVoucher ----

    @Test
    void setVoucherStoresVoucher() {
        SSVoucher v = new SSVoucher(77);
        invoice.setVoucher(v);

        assertThat(invoice.getVoucher()).isSameAs(v);
    }

    // ---- hasOCRNumber ----

    @Test
    void hasOCRNumberReturnsFalseByDefault() {
        assertThat(invoice.hasOCRNumber()).isFalse();
    }

    @Test
    void hasOCRNumberReturnsTrueAfterSet() {
        invoice.setOCRNumber("1234567");

        assertThat(invoice.hasOCRNumber()).isTrue();
    }

    @Test
    void hasOCRNumberReturnsFalseAfterSettingToNull() {
        invoice.setOCRNumber("1234567");
        invoice.setOCRNumber(null);

        assertThat(invoice.hasOCRNumber()).isFalse();
    }

    // ---- setOCRNumber / getOCRNumber ----

    @Test
    void setOCRNumberUpdatesValue() {
        invoice.setOCRNumber("9876543");

        assertThat(invoice.getOCRNumber()).isEqualTo("9876543");
    }

    // ---- setEntered(boolean) / isEntered ----

    @Test
    void setEnteredTrueMarksEntered() {
        invoice.setEntered(true);

        assertThat(invoice.isEntered()).isTrue();
    }

    @Test
    void setEnteredFalseClearsEntered() {
        invoice.setEntered(true);
        invoice.setEntered(false);

        assertThat(invoice.isEntered()).isFalse();
    }

    // ---- setEntered() no-arg ----

    @Test
    void setEnteredNoArgSetsEnteredToTrue() {
        invoice.setEntered();

        assertThat(invoice.isEntered()).isTrue();
    }

    // ---- setOrderNumbers / getOrderNumbers ----

    @Test
    void setOrderNumbersUpdatesValue() {
        invoice.setOrderNumbers("O-001, O-002");

        assertThat(invoice.getOrderNumbers()).isEqualTo("O-001, O-002");
    }

    // ---- setInterestInvoiced / isInterestInvoiced ----

    @Test
    void setInterestInvoicedTrueUpdatesFlag() {
        invoice.setInterestInvoiced(true);

        assertThat(invoice.isInterestInvoiced()).isTrue();
    }

    // ---- setNumRemainders / getNumReminders ----

    @Test
    void setNumRemaindersUpdatesCount() {
        invoice.setNumRemainders(3);

        assertThat(invoice.getNumReminders()).isEqualTo(3);
    }

    // ---- setStockInfluencing / isStockInfluencing ----

    @Test
    void setStockInfluencingFalseUpdatesFlag() {
        invoice.setStockInfluencing(false);

        assertThat(invoice.isStockInfluencing()).isFalse();
    }

    // ---- hasCustomer ----

    @Test
    void hasCustomerReturnsTrueForMatchingCustomerNumber() {
        invoice.setCustomerNr("C100");
        SSCustomer customer = new SSCustomer();
        customer.setNumber("C100");

        assertThat(invoice.hasCustomer(customer)).isTrue();
    }

    @Test
    void hasCustomerReturnsFalseForDifferentCustomerNumber() {
        invoice.setCustomerNr("C100");
        SSCustomer customer = new SSCustomer();
        customer.setNumber("C200");

        assertThat(invoice.hasCustomer(customer)).isFalse();
    }

    @Test
    void hasCustomerReturnsFalseWhenInvoiceCustomerNrIsNull() {
        // iCustomerNr is null by default
        SSCustomer customer = new SSCustomer();
        customer.setNumber("C100");

        assertThat(invoice.hasCustomer(customer)).isFalse();
    }

    // ---- copyFrom / copy constructor ----

    @Test
    void copyConstructorCopiesType() {
        invoice.setType(SSInvoiceType.CASH);
        invoice.setNumber(55);

        SSInvoice copy = new SSInvoice(invoice);

        assertThat(copy.getType()).isEqualTo(SSInvoiceType.CASH);
    }

    @Test
    void copyConstructorCopiesCurrencyRate() {
        invoice.setCurrencyRate(new BigDecimal("9.50"));
        invoice.setNumber(56);

        SSInvoice copy = new SSInvoice(invoice);

        assertThat(copy.getCurrencyRate()).isEqualByComparingTo("9.50");
    }

    @Test
    void copyConstructorCopiesEntered() {
        invoice.setEntered(true);
        invoice.setNumber(57);

        SSInvoice copy = new SSInvoice(invoice);

        assertThat(copy.isEntered()).isTrue();
    }

    // ---- equals ----

    @Test
    void equalsReturnsTrueForSameNumber() {
        invoice.setNumber(100);
        SSInvoice other = new SSInvoice();
        other.setNumber(100);

        assertThat(invoice.equals(other)).isTrue();
    }

    @Test
    void equalsReturnsFalseForDifferentNumbers() {
        invoice.setNumber(100);
        SSInvoice other = new SSInvoice();
        other.setNumber(200);

        assertThat(invoice.equals(other)).isFalse();
    }

    @Test
    void equalsReturnsFalseForNonInvoice() {
        invoice.setNumber(100);

        assertThat(invoice.equals("not an invoice")).isFalse();
    }
}
