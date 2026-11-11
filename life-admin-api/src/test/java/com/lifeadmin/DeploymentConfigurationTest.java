package com.lifeadmin;
import com.lifeadmin.config.DeploymentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
class DeploymentConfigurationTest {
    @Test void rejectsWildcardCredentialsPathsAndInsecureProductionOrigin() {
        var config=new DeploymentConfiguration();var env=new MockEnvironment();
        for(String value:java.util.List.of("*","null","https://user:secret@example.test","https://example.test/path","https://example.test?secret=value","https://example.test#fragment","https://a.test,https://b.test"))
            assertThatThrownBy(()->config.corsConfigurationSource(value,env)).isInstanceOf(IllegalArgumentException.class);
        env.setActiveProfiles("prod");assertThatThrownBy(()->config.corsConfigurationSource("http://localhost:3000",env)).hasMessage("Production FRONTEND_URL must use HTTPS.");
        assertThat(config.corsConfigurationSource("https://app.example.test",env)).isNotNull();
    }
    @Test void unsupportedStorageCannotSilentlyFallBackToLocal() {
        var env=new MockEnvironment().withProperty("app.storage-type","s3");
        assertThatThrownBy(()->new DeploymentConfiguration().corsConfigurationSource("http://localhost:3000",env)).hasMessageContaining("Only local storage is implemented");
    }
}
