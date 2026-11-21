package cn.watsonzhu.runagent.marathon;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "run-agent.marathon", name = {"enabled", "lookup-enabled", "schedule-enabled"},
        havingValue = "true")
class MarathonPodiumSchedule {
    private static final String CRON = "0 0 2 * * *";
    private static final String ZONE = "Asia/Shanghai";
    private final MarathonService service;

    MarathonPodiumSchedule(MarathonService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    void logSchedule() {
        var next = CronExpression.parse(CRON).next(ZonedDateTime.now(ZoneId.of(ZONE)));
        LoggerFactory.getLogger(MarathonPodiumSchedule.class)
                .info("marathon_podium_schedule status=ENABLED next={}", next);
    }

    @Scheduled(cron = CRON, zone = ZONE)
    void refreshRecentPodiums() {
        service.refreshRecentPodiums();
    }
}
