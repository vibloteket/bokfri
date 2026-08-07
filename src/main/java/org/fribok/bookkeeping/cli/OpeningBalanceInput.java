package org.fribok.bookkeeping.cli;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties; import java.math.BigDecimal; import java.util.*;
/** JSON input for replacing an accounting year's opening balances. */
@JsonIgnoreProperties(ignoreUnknown=false)
public class OpeningBalanceInput {private int schemaVersion=1;private List<Row> balances=new ArrayList<>();public int getSchemaVersion(){return schemaVersion;}public void setSchemaVersion(int v){schemaVersion=v;}public List<Row> getBalances(){return balances;}public void setBalances(List<Row> v){balances=v==null?new ArrayList<>():v;}
 @JsonIgnoreProperties(ignoreUnknown=false) public static class Row {private Integer account;private BigDecimal amount;public Integer getAccount(){return account;}public void setAccount(Integer v){account=v;}public BigDecimal getAmount(){return amount;}public void setAmount(BigDecimal v){amount=v;}}
}
