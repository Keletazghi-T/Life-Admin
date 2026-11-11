package com.lifeadmin.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="users") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class User {
    @Id private UUID id;
    @Column(nullable=false,unique=true,length=254) private String email;
    @Column(nullable=false,length=100) private String passwordHash;
    @Column(nullable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    @Column(nullable=false) private long securityVersion;
    @Column(nullable=false) private boolean deletionPending;
    public void changePassword(String hash) { passwordHash=hash; securityVersion++; updatedAt=Instant.now(); }
    public void markDeletionPending() { deletionPending=true; updatedAt=Instant.now(); }
    public User(String email,String passwordHash) {
        id=UUID.randomUUID(); this.email=email; this.passwordHash=passwordHash;
        createdAt=Instant.now(); updatedAt=createdAt;
    }
}
