package com.lifeadmin;
import com.lifeadmin.config.DevelopmentFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
class DevelopmentFeaturesTest {
    @Test void requiresBothExplicitFlagAndDevProfileAndNeverProd() {
        var env = new MockEnvironment();
        assertThat(new DevelopmentFeatures(env, true).mockAnalysisEnabled()).isFalse();
        env.setActiveProfiles("dev");
        assertThat(new DevelopmentFeatures(env, false).mockAnalysisEnabled()).isFalse();
        assertThat(new DevelopmentFeatures(env, true).mockAnalysisEnabled()).isTrue();
        env.setActiveProfiles("dev", "prod");
        assertThat(new DevelopmentFeatures(env, true).mockAnalysisEnabled()).isFalse();
    }
}
