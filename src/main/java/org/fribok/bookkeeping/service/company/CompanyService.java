package org.fribok.bookkeeping.service.company;
import se.swedsoft.bookkeeping.data.*; import se.swedsoft.bookkeeping.data.system.SSDB; import java.util.List;
/** Company creation service. */
public final class CompanyService {private final SSDB db;public CompanyService(SSDB d){db=d;}public List<SSNewCompany> list(){return db.getCompanies();}public SSNewCompany create(SSNewCompany c){if(c==null||c.getName()==null||c.getName().isBlank())throw new IllegalArgumentException("Company name is required");if(list().stream().anyMatch(x->c.getName().equals(x.getName())))throw new IllegalArgumentException("Company name already exists");db.addCompany(c);return c;}}
