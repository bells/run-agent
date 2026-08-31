package cn.watsonzhu.runagent.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class PromptCatalog {

    private static final String CURRENT_DATE_TOKEN = "{{CURRENT_DATE}}";

    private final String chatSystemPrompt;
    private final String intentSystemPrompt;
    private final Clock clock;

    @Autowired
    public PromptCatalog(
            @Value("classpath:prompts/run-agent-system.txt") Resource chatSystemPrompt,
            @Value("classpath:prompts/running-intent-system.txt") Resource intentSystemPrompt) {
        this(chatSystemPrompt, intentSystemPrompt, Clock.systemDefaultZone());
    }

    PromptCatalog(Resource chatSystemPrompt, Resource intentSystemPrompt, Clock clock) {
        this.chatSystemPrompt = read(chatSystemPrompt);
        this.intentSystemPrompt = read(intentSystemPrompt);
        this.clock = clock;
    }

    public String chatSystemPrompt() {
        return chatSystemPrompt;
    }

    public String runningIntentSystemPrompt() {
        return intentSystemPrompt.replace(CURRENT_DATE_TOKEN, LocalDate.now(clock).toString());
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load prompt resource: " + resource.getDescription(), exception);
        }
    }
}
