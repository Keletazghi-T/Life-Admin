package com.lifeadmin.service;
import com.lifeadmin.dto.*;
import com.lifeadmin.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
@Service
public class DashboardService {
    private final JdbcTemplate jdbc; private final DocumentRepository documents; private final InboxService inbox; private final RecentActivityService activity;
    public DashboardService(JdbcTemplate jdbc,DocumentRepository documents,InboxService inbox,RecentActivityService activity) {this.jdbc=jdbc;this.documents=documents;this.inbox=inbox;this.activity=activity;}
    public record Summary(LocalDate today,long documentCount,long openTaskCount,long completedTaskCount,long upcomingDeadlineCount,List<DocumentResponse> recentDocuments,List<UpcomingTaskResponse> needsAttention,List<UpcomingTaskResponse> upcomingDeadlines,long receiptCount,long receiptsNeedingReviewCount,long attentionCount,List<InboxService.Item> attention,List<RecentActivityService.Activity> recentActivity) {}
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Summary get(UUID owner) {
        LocalDate today=LocalDate.now(ZoneOffset.UTC);
        long count=jdbc.queryForObject("select count(*) from documents where user_id=?",Long.class,owner);
        String from=" from tasks t join documents d on d.id=t.document_id where d.user_id=? and d.status='UPLOADED'";
        long open=jdbc.queryForObject("select count(*)"+from+" and t.status='TODO'",Long.class,owner);
        long completed=jdbc.queryForObject("select count(*)"+from+" and t.status='COMPLETED'",Long.class,owner);
        var attention=inbox.items(owner,today);
        long receiptCount=jdbc.queryForObject("select count(*) from receipts where user_id=?",Long.class,owner);
        long receiptReview=jdbc.queryForObject("select count(*) from receipts where user_id=? and status='SAVED' and review_status in ('UPLOADED','NEEDS_REVIEW')",Long.class,owner);
        return new Summary(today,count,open,completed,inbox.upcomingCount(attention,today),
            documents.searchOwned(owner,"%",PageRequest.of(0,5,org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("uploadDate"),org.springframework.data.domain.Sort.Order.asc("id")))).stream().map(DocumentResponse::from).toList(),
            tasks(from+" and t.status='TODO' and (t.due_date<=? or t.due_date is null) order by t.due_date asc nulls last,t.id limit 5",owner,today),
            tasks(from+" and t.status='TODO' and t.due_date>? and t.due_date<=? order by t.due_date,t.id limit 5",owner,today,today.plusDays(30)),receiptCount,receiptReview,attention.size(),attention.stream().limit(8).toList(),activity.get(owner));
    }
    private List<UpcomingTaskResponse> tasks(String query,Object... args) {
        return jdbc.query("select t.id,t.title,t.due_date,d.id as document_id,d.display_name,t.development_mock"+query,
            (rs,row)->new UpcomingTaskResponse(rs.getObject("id",UUID.class),rs.getString("title"),rs.getObject("due_date",LocalDate.class),rs.getObject("document_id",UUID.class),rs.getString("display_name"),rs.getBoolean("development_mock")),args);
    }
}
