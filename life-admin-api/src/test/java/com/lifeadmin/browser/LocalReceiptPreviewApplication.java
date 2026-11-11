package com.lifeadmin.browser;

import com.lifeadmin.LifeAdminApplication;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;
import java.nio.file.*;

/** Optional launcher for the existing retained local preview. Not packaged in the application JAR. */
public class LocalReceiptPreviewApplication {
    public static void main(String[] args) throws Exception {
        Path local=Path.of(args[0]).resolve(".local/accounts-preview");
        // Never silently create a different database when resuming this workspace's preview.
        if(!Files.exists(local.resolve("postgres/PG_VERSION"))) throw new IllegalStateException("Existing preview database not found. Use bootRun with your normal database configuration instead.");
        var postgres=EmbeddedPostgres.builder().setDataDirectory(local.resolve("postgres")).setCleanDataDirectory(false).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {try {postgres.close();} catch(Exception ignored) {}}));
        System.setProperty("spring.datasource.url",postgres.getJdbcUrl("postgres","postgres"));
        System.setProperty("spring.datasource.username","postgres");System.setProperty("spring.datasource.password","postgres");
        System.setProperty("app.storage-directory",local.resolve("uploads").toString());
        System.setProperty("server.port","8084");System.setProperty("app.frontend-url","http://localhost:3001");
        System.setProperty("debug","false");System.setProperty("spring.profiles.active","dev");System.setProperty("app.development.mock-analysis-enabled","true");
        // No key property override: application.yml reads OPENAI_API_KEY from this process environment.
        System.out.println(System.getenv("OPENAI_API_KEY") == null || System.getenv("OPENAI_API_KEY").isBlank()
            ? "Receipt photo extraction is not configured; manual review remains available."
            : "Receipt photo extraction credential is present (value not displayed).");
        SpringApplication.run(LifeAdminApplication.class);
    }
}
