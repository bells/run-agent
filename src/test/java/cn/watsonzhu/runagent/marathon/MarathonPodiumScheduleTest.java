package cn.watsonzhu.runagent.marathon;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.SimpleTriggerContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MarathonPodiumScheduleTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MarathonPodiumSchedule.class)
            .withBean(MarathonService.class, () -> mock(MarathonService.class))
            .withPropertyValues("run-agent.marathon.enabled=true", "run-agent.marathon.lookup-enabled=true");

    @Test
    void registersAnActualSpringCronTriggerForTwoInTheMorningBeijingTime() {
        runner.withPropertyValues("run-agent.marathon.schedule-enabled=true").run(context -> {
            var clock = Clock.fixed(Instant.parse("2026-11-22T17:59:59Z"), ZoneOffset.UTC);
            var tasks = context.getBean(ScheduledTaskHolder.class).getScheduledTasks();
            assertThat(tasks).singleElement().satisfies(task -> {
                assertThat(task.getTask()).isInstanceOfSatisfying(CronTask.class, cron ->
                        assertThat(cron.getTrigger().nextExecution(new SimpleTriggerContext(clock)))
                                .isEqualTo(Instant.parse("2026-11-22T18:00:00Z")));
            });
        });
    }

    @Test
    void scheduleIsOptIn() {
        runner.run(context -> assertThat(context).doesNotHaveBean(MarathonPodiumSchedule.class));
        runner.withPropertyValues("run-agent.marathon.schedule-enabled=true", "run-agent.marathon.lookup-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(MarathonPodiumSchedule.class));
    }
}
