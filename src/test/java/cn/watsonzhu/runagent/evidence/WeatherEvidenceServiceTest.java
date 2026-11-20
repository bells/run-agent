package cn.watsonzhu.runagent.evidence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Clock;
import java.util.Optional;

import cn.watsonzhu.runagent.mcp.McpCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class WeatherEvidenceServiceTest {
    @Test
    void usesResolvedCoordinatesAndTheRequestedHour() {
        @SuppressWarnings("unchecked")
        ObjectProvider<McpCatalog> beans = mock(ObjectProvider.class);
        McpCatalog catalog = mock(McpCatalog.class);
        when(beans.getIfAvailable()).thenReturn(catalog);
        ToolCallback search = mock(ToolCallback.class);
        ToolCallback forecast = mock(ToolCallback.class);
        when(catalog.findTool("weather", "openmeteo_search_locations")).thenReturn(Optional.of(search));
        when(catalog.findTool("weather", "openmeteo_get_forecast")).thenReturn(Optional.of(forecast));
        when(catalog.findTool("weather", "openmeteo_get_air_quality")).thenReturn(Optional.empty());
        when(search.call(any(), any())).thenReturn("""
                {"results":[{"name":"上海","latitude":31.2,"longitude":121.5,"timezone":"Asia/Shanghai","population":24874500},
                {"name":"上海","latitude":29.3,"longitude":121.1,"timezone":"Asia/Shanghai","population":null}]}
                """);
        when(forecast.call(any(), any())).thenReturn("""
                {"hourly":[{"time":"2026-11-20T18:00","temperature_2m":18.1,"precipitation_probability":0},
                {"time":"2026-11-20T19:00","temperature_2m":17.5,"precipitation_probability":30}]}
                """);
        var weather = new WeatherEvidenceService(beans, new JsonMapper(),
                Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC))
                .read(new AdviceContext("上海", LocalDateTime.parse("2026-11-20T19:15")));
        assertThat(weather.status()).isEqualTo(RunningEvidenceService.EvidenceStatus.READY);
        assertThat(weather.validAt()).isEqualTo("2026-11-20T19:00");
        assertThat(weather.temperatureC()).isEqualTo(17.5);
        assertThat(weather.precipitationProbability()).isEqualTo(30);
        verify(forecast).call(argThat(payload -> payload.contains("31.2")
                && payload.contains("121.5") && payload.contains("Asia/Shanghai")
                && !payload.contains("上海")), any());
    }

    @Test
    void explicitChatCityOverridesTheSelectedCity() {
        var context = new AdviceContext("深圳", null).withChatHints("我在上海，今晚七点跑间歇。");
        assertThat(context.city()).isEqualTo("上海");
        assertThat(context.resolveTime(LocalDateTime.parse("2026-11-20T17:00")))
                .isEqualTo(LocalDateTime.parse("2026-11-20T19:00"));
    }

    @Test
    void publicAnswersCanSummarizeButCannotReturnLongVerbatimExcerpts() {
        String excerpt = "请按自己当前能够稳定维持的跑步强度选择训练配速。".repeat(6);
        var bundle = new RunningEvidenceService.Bundle(RunningEvidenceService.Bundle.empty().evidence(),
                "internal context", java.util.List.of(excerpt));
        assertThat(bundle.allowsAnswer("选择自己能稳定维持的配速。")).isTrue();
        assertThat(bundle.allowsAnswer("**回答** " + excerpt.replace("。", "。\n"))).isFalse();
    }
}
