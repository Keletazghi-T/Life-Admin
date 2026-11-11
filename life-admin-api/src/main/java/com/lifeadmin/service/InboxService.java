package com.lifeadmin.service;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;

/** Read-only, account-scoped attention projection. No alerts or sample records are created. */
@Service
public class InboxService {
    private final JdbcTemplate jdbc;
    public InboxService(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public record Item(String id,String type,String title,String context,List<String> reasons,String status,
            LocalDate date,String dateLabel,Instant createdAt,String href,String action,UUID taskId,boolean developmentSample,LocalDate deadlineDate) {}
    public record Page(List<Item> items,long totalCount,long attentionCount,boolean hasMore,LocalDate today) {}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page get(UUID owner,String filter,String sort,int page) {
        if(!List.of("all","documents","receipts","tasks","deadlines").contains(filter) || !List.of("urgent","newest").contains(sort) || page<0 || page>1_000_000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid inbox filter, sort and page.");
        var today=LocalDate.now(ZoneOffset.UTC);var all=items(owner,today);
        var selected=all.stream().filter(i->switch(filter){case "documents"->i.type().equals("DOCUMENT") || i.type().equals("DEADLINE");case "receipts"->i.type().equals("RECEIPT");case "tasks"->i.taskId()!=null;case "deadlines"->i.date()!=null && !i.type().equals("RECEIPT");default->true;}).sorted(sort.equals("newest")?Comparator.comparing(Item::createdAt).reversed().thenComparing(Item::id):urgency(today)).toList();
        int start=(int)Math.min((long)page*25,selected.size()),end=Math.min(start+25,selected.size());
        return new Page(selected.subList(start,end),selected.size(),all.size(),end<selected.size(),today);
    }
    public List<Item> items(UUID owner,LocalDate today) {
        var result=new ArrayList<Item>();
        // One grouped query for tasks/reminders; a reminder never creates a second task item.
        result.addAll(jdbc.query("""
            select t.id,t.title,t.due_date,t.created_at,t.development_mock,d.id document_id,d.display_name,
                min(r.reminder_date) reminder_date
            from tasks t join documents d on d.id=t.document_id
            left join reminders r on r.task_id=t.id and r.status='PENDING'
            where d.user_id=? and d.status='UPLOADED' and t.status='TODO'
            group by t.id,d.id
            """,(rs,n)->{
                var id=rs.getObject("id",UUID.class);var doc=rs.getObject("document_id",UUID.class);
                var due=rs.getObject("due_date",LocalDate.class);var reminder=rs.getObject("reminder_date",LocalDate.class);
                boolean reminderFirst=reminder!=null && (due==null || reminder.isBefore(due));var date=reminderFirst?reminder:due;
                var reasons=new ArrayList<String>();
                if(reminderFirst){reasons.add("Reminder needs attention"+(due==null?"":" · Task due "+due));}
                else reasons.add(due==null?"Unfinished action":due.isBefore(today)?"Task is overdue":due.equals(today)?"Task is due today":"Task has an upcoming deadline");
                return new Item("task:"+id,"TASK",rs.getString("title"),rs.getString("display_name"),List.copyOf(reasons),datedStatus(date,today),date,reminderFirst?"Reminder":"Due",rs.getTimestamp("created_at").toInstant(),"/documents/"+doc+"#task-"+id,"Open task",id,rs.getBoolean("development_mock"),due!=null?due:reminder);
            },owner));
        // Analysis represents a document only before any action plan exists. Completed plans
        // must not resurrect a stale analysis warning. Ordinary uploads aren't problems.
        result.addAll(jdbc.query("""
            select d.id,d.display_name,d.upload_date,d.status,a.action_required,a.deadline,a.source
            from documents d left join document_analyses a on a.document_id=d.id
            where d.user_id=? and (d.status='DELETION_PENDING' or
              (d.status='UPLOADED' and (a.action_required=true or (a.action_required is null and jsonb_array_length(a.next_steps)>0))
               and not exists(select 1 from tasks t where t.document_id=d.id)))
            """,(rs,n)->{
                var id=rs.getObject("id",UUID.class);boolean pending="DELETION_PENDING".equals(rs.getString("status"));var date=pending?null:rs.getObject("deadline",LocalDate.class);
                return new Item("document:"+id,date==null?"DOCUMENT":"DEADLINE",rs.getString("display_name"),"Document",List.of(pending?"Deletion is incomplete. Retry cleanup.":"Review the document's suggested actions and verify any deadline against the original."),pending?"INCOMPLETE":datedStatus(date,today),date,"Suggested deadline",rs.getTimestamp("upload_date").toInstant(),"/documents/"+id,pending?"Open document":"Review document",null,"DEVELOPMENT_MOCK".equals(rs.getString("source")),date);
            },owner));
        // One receipt item combines review status and missing metadata (including category).
        result.addAll(jdbc.query("""
            select id,merchant,original_file_name,purchase_date,amount,category,review_status,status,created_at,extraction_source
            from receipts where user_id=? and (status='DELETION_PENDING' or review_status<>'REVIEWED'
                or merchant is null or btrim(merchant)='' or purchase_date is null or amount is null or category is null)
            """,(rs,n)->{
                var id=rs.getObject("id",UUID.class);boolean pending="DELETION_PENDING".equals(rs.getString("status"));boolean review=!"REVIEWED".equals(rs.getString("review_status"));var reasons=new ArrayList<String>();
                if(pending)reasons.add("Deletion is incomplete. Retry cleanup.");
                else {
                    if(review)reasons.add("Receipt needs review");
                    if(rs.getString("merchant")==null || rs.getString("merchant").isBlank())reasons.add("Missing merchant");
                    if(rs.getObject("purchase_date")==null)reasons.add("Missing purchase date");
                    if(rs.getBigDecimal("amount")==null)reasons.add("Missing amount");
                    if(rs.getString("category")==null)reasons.add("Missing category");
                }
                var name=rs.getString("merchant");var created=rs.getTimestamp("created_at").toInstant();
                return new Item("receipt:"+id,"RECEIPT",name==null || name.isBlank()?rs.getString("original_file_name"):name,"Receipt",List.copyOf(reasons),pending?"INCOMPLETE":review?"NEEDS_REVIEW":"MISSING_INFORMATION",created.atZone(ZoneOffset.UTC).toLocalDate(),"Uploaded",created,"/receipts/"+id,pending?"Open receipt":"Review receipt",null,"DEVELOPMENT_SAMPLE".equals(rs.getString("extraction_source")),null);
            },owner));
        result.sort(urgency(today));return List.copyOf(result);
    }
    public long upcomingCount(List<Item> items,LocalDate today){return items.stream().filter(i->i.deadlineDate()!=null && i.deadlineDate().isAfter(today) && !i.deadlineDate().isAfter(today.plusDays(30))).count();}
    private static String datedStatus(LocalDate date,LocalDate today){return date==null?"OPEN":date.isBefore(today)?"OVERDUE":date.equals(today)?"DUE_TODAY":"UPCOMING";}
    private static Comparator<Item> urgency(LocalDate today){return Comparator.comparingInt((Item i)->switch(i.status()){case "OVERDUE"->0;case "DUE_TODAY"->1;case "UPCOMING"->i.date().isAfter(today.plusDays(30))?4:2;case "NEEDS_REVIEW"->3;default->5;}).thenComparing(i->i.type().equals("RECEIPT")?null:i.date(),Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Item::createdAt,Comparator.reverseOrder()).thenComparing(Item::id);}
}
