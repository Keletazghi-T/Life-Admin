package com.lifeadmin.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.util.UUID;
@Entity @Table(name="reminders") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Reminder {
    @Id private UUID id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="task_id", nullable=false) private Task task;
    @Column(nullable=false) private LocalDate reminderDate;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) private ReminderStatus status;
    @Column(nullable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    public Reminder(Task task, LocalDate date) { id=UUID.randomUUID(); this.task=task; status=ReminderStatus.PENDING; createdAt=Instant.now(); reschedule(date); }
    public void reschedule(LocalDate date) { reminderDate=date; updatedAt=Instant.now(); }
    public void changeStatus(ReminderStatus value) { status=value; updatedAt=Instant.now(); }
}
