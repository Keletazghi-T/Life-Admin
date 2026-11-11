package com.lifeadmin.service;
import com.lifeadmin.dto.*;
import com.lifeadmin.entity.*;
import com.lifeadmin.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;
@Service
public class ReminderService {
    private final ReminderRepository reminders;
    private final TaskRepository tasks;
    public ReminderService(ReminderRepository reminders,TaskRepository tasks) { this.reminders=reminders; this.tasks=tasks; }
    private LocalDate today() { return LocalDate.now(ZoneOffset.UTC); }
    private ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Task or reminder not found."); }
    private Task ownedTask(UUID owner,UUID id) { return tasks.findOwnedForUpdate(id,owner).orElseThrow(this::missing); }
    private Reminder ownedReminder(UUID owner,UUID id) {
        UUID taskId=reminders.findOwnedTaskId(id,owner).orElseThrow(this::missing);
        ownedTask(owner,taskId);
        return reminders.findOwned(id,owner).orElseThrow(this::missing);
    }
    private LocalDate validate(Task task,ReminderRequest input,UUID exclude) {
        if(task.getStatus()==TaskStatus.COMPLETED) throw new ResponseStatusException(HttpStatus.CONFLICT,"Reopen this task before adding or changing reminders.");
        if(task.getDueDate()==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Set a task due date before adding reminders.");
        if((input.reminderDate()==null)==(input.daysBefore()==null)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose either a reminder date or a preset.");
        LocalDate date=input.reminderDate();
        if(input.daysBefore()!=null) {
            if(!List.of(1,3,7).contains(input.daysBefore())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose 1, 3, or 7 days before.");
            date=task.getDueDate().minusDays(input.daysBefore());
        }
        if(date.isBefore(today())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Reminder date cannot be in the past.");
        if(date.isAfter(task.getDueDate())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Reminder date cannot be after the task due date.");
        if(reminders.existsByTaskIdAndReminderDateAndIdNot(task.getId(),date,exclude)) throw new ResponseStatusException(HttpStatus.CONFLICT,"A reminder already exists for this task and date.");
        return date;
    }
    @Transactional public List<ReminderResponse> list(UUID owner,UUID taskId) {
        ownedTask(owner,taskId);
        return reminders.findByTaskIdOrderByReminderDateAscIdAsc(taskId).stream().map(r->ReminderResponse.from(r,today())).toList();
    }
    @Transactional public ReminderResponse create(UUID owner,UUID taskId,ReminderRequest input) {
        Task task=ownedTask(owner,taskId);
        LocalDate date=validate(task,input,new UUID(0,0));
        return ReminderResponse.from(reminders.save(new Reminder(task,date)),today());
    }
    @Transactional public ReminderResponse update(UUID owner,UUID id,ReminderRequest input) {
        Reminder r=ownedReminder(owner,id); r.reschedule(validate(r.getTask(),input,id)); return ReminderResponse.from(r,today());
    }
    @Transactional public ReminderResponse status(UUID owner,UUID id,ReminderStatus status) {
        Reminder r=ownedReminder(owner,id);
        if(status==ReminderStatus.PENDING) validate(r.getTask(),new ReminderRequest(r.getReminderDate(),null),id);
        r.changeStatus(status); return ReminderResponse.from(r,today());
    }
    @Transactional public void delete(UUID owner,UUID id) { reminders.delete(ownedReminder(owner,id)); }
}
