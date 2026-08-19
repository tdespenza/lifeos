package com.lifeos.media;

import com.lifeos.media.authorization.MediaIdentityProperties;
import com.lifeos.media.config.MediaProperties;
import com.lifeos.media.config.MediaTaskGoalProperties;
import com.lifeos.media.config.MediaTrustLedgerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Owner-scoped media metadata, bounded uploads, and live-session signaling foundation. */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
    MediaProperties.class,
    MediaIdentityProperties.class,
    MediaTaskGoalProperties.class,
    MediaTrustLedgerProperties.class
})
public class MediaServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaServiceApplication.class, args);
    }
}
