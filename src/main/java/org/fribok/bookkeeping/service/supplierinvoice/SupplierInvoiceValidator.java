package org.fribok.bookkeeping.service.supplierinvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoice;
import se.swedsoft.bookkeeping.data.SSSupplierInvoiceRow;
import java.util.*;
public final class SupplierInvoiceValidator {
 private SupplierInvoiceValidator(){}
 public static SupplierInvoiceValidationResult validate(SSSupplierInvoice i){List<SupplierInvoiceValidationIssue>x=new ArrayList<>();
  if(i==null){x.add(q("SUPPLIER_INVOICE_REQUIRED",null,null,"Leverantörsfakturan saknas."));return new SupplierInvoiceValidationResult(false,x);}
  if(i.getSupplierNr()==null||i.getSupplierName()==null)x.add(q("SUPPLIER_INVOICE_SUPPLIER_REQUIRED","supplierNumber",null,"Giltig leverantör saknas."));
  if(i.getLocalDate()==null)x.add(q("SUPPLIER_INVOICE_DATE_REQUIRED","date",null,"Fakturadatum saknas."));
  if(i.getLocalDueDate()==null)x.add(q("SUPPLIER_INVOICE_DUE_DATE_REQUIRED","dueDate",null,"Förfallodatum saknas."));
  else if(i.getLocalDate()!=null&&i.getLocalDueDate().isBefore(i.getLocalDate()))x.add(q("SUPPLIER_INVOICE_DUE_DATE_INVALID","dueDate",null,"Förfallodatum ligger före fakturadatum."));
  if(i.getRows().isEmpty())x.add(q("SUPPLIER_INVOICE_ROWS_REQUIRED","rows",null,"Fakturan saknar rader."));
  for(int n=0;n<i.getRows().size();n++){SSSupplierInvoiceRow r=i.getRows().get(n);int k=n+1;
   if(r==null||r.getDescription()==null||r.getDescription().isBlank())x.add(q("SUPPLIER_INVOICE_ROW_DESCRIPTION_REQUIRED","description",k,"Radbeskrivning saknas."));
   if(r==null||r.getQuantity()==null||r.getQuantity()<=0)x.add(q("SUPPLIER_INVOICE_ROW_QUANTITY_INVALID","quantity",k,"Antal måste vara positivt."));
   if(r==null||r.getUnitprice()==null||r.getUnitprice().signum()<0)x.add(q("SUPPLIER_INVOICE_ROW_PRICE_INVALID","unitPrice",k,"Pris får inte vara negativt."));
   if(r==null||r.getAccountNr()==null||r.getAccountNr()<=0)x.add(q("SUPPLIER_INVOICE_ROW_ACCOUNT_REQUIRED","account",k,"Kostnadskonto saknas."));}
  if(i.getTaxSum().signum()<0)x.add(q("SUPPLIER_INVOICE_VAT_INVALID","vat",null,"Moms får inte vara negativ."));
  return new SupplierInvoiceValidationResult(x.isEmpty(),x);}
 private static SupplierInvoiceValidationIssue q(String c,String f,Integer r,String m){return new SupplierInvoiceValidationIssue(c,f,r,m);}
}
