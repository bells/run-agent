package cn.watsonzhu.runagent.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("weather-mcp")
public class WeatherMcpConfig {
    public WeatherMcpConfig(
            @Value("${spring.ai.mcp.client.streamable-http.connections.weather.url:}") String url) {
        try {
            URI uri = URI.create(url);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("weather-mcp requires spring.ai.mcp.client.streamable-http.connections.weather.url "
                    + "as an absolute HTTP(S) endpoint without credentials, query or fragment");
        }
    }
}
