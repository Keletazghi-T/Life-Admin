package com.lifeadmin;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class LegacyOwnershipMigrationTest {
    @Test void anonymousRecordsAreNeverAssignedToNewAccountsOrDeleted() throws Exception {
        try(var postgres=EmbeddedPostgres.builder().start()) {
            String url=postgres.getJdbcUrl("postgres","postgres");
            Flyway.configure().dataSource(url,"postgres","postgres").target("4").load().migrate();
            try(var connection=postgres.getPostgresDatabase().getConnection(); var stmt=connection.prepareStatement(
                "insert into documents(id,user_id,original_file_name,stored_file_name,file_type,file_size,upload_date,status) values (?,?, 'Legacy.pdf','legacy.pdf','application/pdf',10,now(),'UPLOADED')")) {
                stmt.setObject(1,UUID.randomUUID()); stmt.setObject(2,UUID.randomUUID()); stmt.executeUpdate();
            }
            assertThatThrownBy(()->Flyway.configure().dataSource(url,"postgres","postgres").load().migrate())
                .hasMessageContaining("Legacy anonymous documents require an explicit migration plan");
            try(var connection=postgres.getPostgresDatabase().getConnection(); var stmt=connection.createStatement(); var result=stmt.executeQuery("select count(*) from documents")) {
                result.next(); assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }
}
