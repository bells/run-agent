package cn.watsonzhu.runagent.evidence;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.watsonzhu.runagent.evidence.RunningEvidenceService.EvidenceStatus;
import cn.watsonzhu.runagent.evidence.RunningEvidenceService.Weather;
import cn.watsonzhu.runagent.mcp.McpCatalog;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class WeatherEvidenceService {
    private final ObjectProvider<McpCatalog> catalogs;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    WeatherEvidenceService(ObjectProvider<McpCatalog> catalogs, ObjectMapper json) {
        this(catalogs, json, Clock.systemUTC());
    }

    WeatherEvidenceService(ObjectProvider<McpCatalog> catalogs, ObjectMapper json, Clock clock) {
        this.catalogs = catalogs;
        this.json = json;
        this.clock = clock;
    }

    Weather read(AdviceContext context) {
        var catalog = catalogs.getIfAvailable();
        if (catalog == null) return Weather.status(EvidenceStatus.UNAVAILABLE);

        boolean chinese = context.city().codePoints().anyMatch(
                point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN);
        var search = call(catalog, "openmeteo_search_locations", Map.of(
                "name", context.city(), "count", 5, "language", chinese ? "zh" : "en"));
        if (search == null) return Weather.status(EvidenceStatus.UNAVAILABLE);
        var result = search.path("results");
        var location = location(result, context.city());
        if (location == null) return Weather.status(EvidenceStatus.NO_MATCH);

        double latitude = location.path("latitude").asDouble(Double.NaN);
        double longitude = location.path("longitude").asDouble(Double.NaN);
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            return Weather.status(EvidenceStatus.UNAVAILABLE);
        }
        String timezone = location.path("timezone").asString();
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (RuntimeException exception) {
            return Weather.status(EvidenceStatus.UNAVAILABLE);
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), zone).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime target = context.resolveTime(now).truncatedTo(ChronoUnit.HOURS);
        long days = ChronoUnit.DAYS.between(now.toLocalDate(), target.toLocalDate());
        if (target.isBefore(now) || days > 6) return Weather.status(EvidenceStatus.NO_MATCH);

        Map<String, Object> arguments = new LinkedHashMap<>(Map.of("latitude", latitude, "longitude", longitude,
                "timezone", timezone, "forecast_days", days + 1));
        arguments.put("hourly_variables", List.of("temperature_2m", "apparent_temperature",
                "precipitation_probability", "wind_speed_10m"));
        var hourly = call(catalog, "openmeteo_get_forecast", arguments);
        if (hourly == null) return Weather.status(EvidenceStatus.UNAVAILABLE);
        var values = hour(hourly.path("hourly"), target);
        if (values == null) return Weather.status(EvidenceStatus.NO_MATCH);
        Double temperature = decimal(values.field("temperature_2m"));
        Double apparent = decimal(values.field("apparent_temperature"));
        Integer precipitation = integer(values.field("precipitation_probability"));
        Double wind = decimal(values.field("wind_speed_10m"));
        if (temperature == null && apparent == null && precipitation == null && wind == null) {
            return Weather.status(EvidenceStatus.NO_MATCH);
        }
        String city = location.path("name").asString(context.city());
        Integer airQuality = Mono.fromCallable(() -> airQuality(catalog, arguments, target, days))
                .subscribeOn(Schedulers.boundedElastic()).timeout(Duration.ofMillis(900))
                .onErrorComplete().block();
        return new Weather(EvidenceStatus.READY, city, timezone, target.toString(), clock.instant().toString(),
                temperature, apparent, precipitation, wind, airQuality);
    }

    private Integer airQuality(McpCatalog catalog, Map<String, Object> arguments, LocalDateTime target, long days) {
        if (days > 4) return null;
        arguments.put("hourly_variables", List.of("european_aqi"));
        var data = call(catalog, "openmeteo_get_air_quality", arguments);
        if (data == null) return null;
        var values = hour(data.path("hourly"), target);
        return values == null ? null : integer(values.field("european_aqi"));
    }

    private JsonNode call(McpCatalog catalog, String tool, Map<String, ?> arguments) {
        try {
            var callback = catalog.findTool("weather", tool);
            if (callback.isEmpty()) return null;
            var result = json.readTree(callback.orElseThrow().call(json.writeValueAsString(arguments),
                    new ToolContext(Map.of())));
            if (result.isArray() && result.size() == 1 && result.path(0).path("text").isString()) {
                result = json.readTree(result.path(0).path("text").asString());
            }
            if (result.isObject() && result.path("data").isObject()) result = result.path("data");
            return result.isObject() && !result.path("error").asBoolean(false) ? result : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static JsonNode location(JsonNode results, String city) {
        if (!results.isArray() || results.isEmpty()) return null;
        if (results.size() == 1) return results.path(0);
        JsonNode best = null;
        long runnerUpPopulation = 0;
        int exactMatches = 0;
        for (var candidate : results) {
            if (city.equalsIgnoreCase(candidate.path("name").asString())
                    || (city.endsWith("市") && city.substring(0, city.length() - 1)
                            .equals(candidate.path("name").asString()))) {
                exactMatches++;
                long candidatePopulation = population(candidate);
                if (best == null || candidatePopulation > population(best)) {
                    runnerUpPopulation = population(best);
                    best = candidate;
                } else {
                    runnerUpPopulation = Math.max(runnerUpPopulation, candidatePopulation);
                }
            }
        }
        if (exactMatches <= 1) return best;
        // City names can also refer to small localities. Choose a clearly dominant city only.
        long bestPopulation = population(best);
        return bestPopulation >= 100_000 && bestPopulation / 10 > runnerUpPopulation ? best : null;
    }

    private static long population(JsonNode candidate) {
        return candidate == null ? 0 : candidate.path("population").asLong(0);
    }

    private static Hour hour(JsonNode hourly, LocalDateTime target) {
        if (hourly.isArray()) {
            for (var row : hourly) {
                if (matchesTime(row.path("time").asString(), target)) return new Hour(row, -1);
            }
            return null;
        }
        var times = hourly.path("time");
        for (int i = 0; i < times.size(); i++) {
            if (matchesTime(times.path(i).asString(), target)) return new Hour(hourly, i);
        }
        return null;
    }

    private static boolean matchesTime(String time, LocalDateTime target) {
        return target.toString().equals(time) || (target + ":00").equals(time);
    }

    private static Double decimal(JsonNode value) {
        return value.isNumber() && Double.isFinite(value.asDouble()) ? value.asDouble() : null;
    }

    private static Integer integer(JsonNode value) {
        return value.isNumber() && value.canConvertToInt() ? (int) Math.round(value.asDouble()) : null;
    }

    private record Hour(JsonNode values, int index) {
        JsonNode field(String variable) {
            var field = values.path(variable);
            return index < 0 ? field : field.path(index);
        }
    }
}
