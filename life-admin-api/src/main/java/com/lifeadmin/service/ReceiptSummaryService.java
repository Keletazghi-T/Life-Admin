package com.lifeadmin.service;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
@Service
public class ReceiptSummaryService {
    private final JdbcTemplate jdbc;public ReceiptSummaryService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record CategoryTotal(String category,long count,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount){}
    public record Summary(int year,String currency,long receiptCount,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalAmount,long taxRelevantCount,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal taxRelevantAmount,long missingCategoryCount,long missingInformationCount,List<CategoryTotal> categories){}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Summary get(UUID owner,int year){
        ReceiptService.validateYear(year);var first=LocalDate.of(year,1,1);var last=first.plusYears(1);
        String where=" from receipts where user_id=? and status='SAVED' and review_status='REVIEWED' and purchase_date>=? and purchase_date<?";
        var categories=jdbc.query("select category,count(*) as count,sum(amount) as amount"+where+" and tax_relevant=true group by category order by category nulls last",(rs,i)->new CategoryTotal(rs.getString("category"),rs.getLong("count"),rs.getBigDecimal("amount")),owner,first,last);
        return jdbc.queryForObject("select count(*) as count,coalesce(sum(amount),0) as total,count(*) filter(where tax_relevant) as tax_count,coalesce(sum(amount) filter(where tax_relevant),0) as tax_total,count(*) filter(where tax_relevant and category is null) as missing_category,count(*) filter(where tax_relevant and (merchant is null or btrim(merchant)='' or purchase_date is null or amount is null)) as missing_information"+where,
            (rs,i)->new Summary(year,"USD",rs.getLong("count"),rs.getBigDecimal("total"),rs.getLong("tax_count"),rs.getBigDecimal("tax_total"),rs.getLong("missing_category"),rs.getLong("missing_information"),categories),owner,first,last);
    }
}
