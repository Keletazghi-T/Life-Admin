package com.lifeadmin.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.*;
import java.time.*;
import java.util.*;

@Service
public class ReceiptInsightsService {
    private final JdbcTemplate jdbc;
    public ReceiptInsightsService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Breakdown(String name, long count, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount) {}
    public record Month(int month, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount) {}
    public record Insights(int year, String currency, List<Integer> years, long receiptCount,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal totalAmount,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal averageAmount,
        List<Breakdown> categories, List<Breakdown> merchants, List<Month> months) {}

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Insights get(UUID owner, int year) {
        ReceiptService.validateYear(year);
        var first=LocalDate.of(year,1,1);
        var last=first.plusYears(1);
        // Aggregate confirmed values directly, independently of the receipt list's pagination.
        String where=" from receipts where user_id=? and status='SAVED' and review_status='REVIEWED'"
            +" and currency='USD' and amount is not null and purchase_date>=? and purchase_date<?";
        var categories=jdbc.query("select category as name,count(*) as count,sum(amount) as amount"+where
            +" group by category order by amount desc, category nulls last",
            (rs,i)->new Breakdown(rs.getString("name"),rs.getLong("count"),rs.getBigDecimal("amount")),owner,first,last);
        var merchants=jdbc.query("select merchant as name,count(*) as count,sum(amount) as amount"+where
            +" group by merchant order by amount desc, merchant nulls last",
            (rs,i)->new Breakdown(rs.getString("name"),rs.getLong("count"),rs.getBigDecimal("amount")),owner,first,last);
        var byMonth=new HashMap<Integer,BigDecimal>();
        jdbc.query("select extract(month from purchase_date)::int as month,sum(amount) as amount"+where+" group by 1",
            (org.springframework.jdbc.core.RowCallbackHandler) rs->byMonth.put(rs.getInt("month"),rs.getBigDecimal("amount")),owner,first,last);
        var months=new ArrayList<Month>();
        for(int month=1;month<=12;month++) months.add(new Month(month,byMonth.getOrDefault(month,new BigDecimal("0.00"))));
        // Offer years containing dated receipts, even if they have not yet been reviewed.
        var years=new TreeSet<Integer>(Comparator.reverseOrder());
        years.add(LocalDate.now(ZoneOffset.UTC).getYear());
        years.addAll(jdbc.queryForList("select distinct extract(year from purchase_date)::int from receipts where user_id=? and status='SAVED' and purchase_date is not null",Integer.class,owner));
        long count=categories.stream().mapToLong(Breakdown::count).sum();
        BigDecimal total=categories.stream().map(Breakdown::amount).reduce(new BigDecimal("0.00"),BigDecimal::add);
        BigDecimal average=count==0?new BigDecimal("0.00"):total.divide(BigDecimal.valueOf(count),2,RoundingMode.HALF_UP);
        return new Insights(year,"USD",List.copyOf(years),count,total,average,categories,merchants,months);
    }
}
