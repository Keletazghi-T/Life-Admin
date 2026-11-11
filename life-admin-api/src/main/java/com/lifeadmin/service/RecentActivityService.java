package com.lifeadmin.service;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.*;
@Service
public class RecentActivityService {
 private final JdbcTemplate jdbc;
 public RecentActivityService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Activity(String id,String title,String name,Instant occurredAt,String href,boolean developmentSample){}
 public List<Activity> get(UUID owner){
  // UNION ALL with a single LIMIT; no entity traversal or persistent activity/audit table.
  return jdbc.query("""
   select * from (
    select 'document:'||id id,'Document uploaded' title,display_name name,upload_date occurred_at,'/documents/'||id href,false sample
     from documents where user_id=? and status='UPLOADED'
    union all
    select 'receipt:'||id,'Receipt added',coalesce(nullif(merchant,''),original_file_name),created_at,'/receipts/'||id,extraction_source='DEVELOPMENT_SAMPLE'
     from receipts where user_id=? and status='SAVED'
    union all
    select 'review:'||id,'Receipt reviewed',coalesce(nullif(merchant,''),original_file_name),updated_at,'/receipts/'||id,extraction_source='DEVELOPMENT_SAMPLE'
     from receipts where user_id=? and status='SAVED' and review_status='REVIEWED'
    union all
    select 'task:'||t.id,'Task completed',t.title,t.updated_at,'/documents/'||d.id||'#task-'||t.id,t.development_mock
     from tasks t join documents d on d.id=t.document_id where d.user_id=? and d.status='UPLOADED' and t.status='COMPLETED'
   ) activity order by occurred_at desc,id limit 5
   """,(rs,n)->new Activity(rs.getString("id"),rs.getString("title"),rs.getString("name"),rs.getTimestamp("occurred_at").toInstant(),rs.getString("href"),rs.getBoolean("sample")),owner,owner,owner,owner);
 }
}
