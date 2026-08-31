package cn.watsonzhu.runagent.agent;

import java.time.Duration;

import cn.watsonzhu.runagent.exception.AiRequestException;
import cn.watsonzhu.runagent.exception.StructuredOutputException;
import cn.watsonzhu.runagent.model.RunningIntent;
import cn.watsonzhu.runagent.prompt.PromptCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class RunAgentService {

    private static final Logger log = LoggerFactory.getLogger(RunAgentService.class);

    private final ChatClient chatClient;
    private final PromptCatalog prompts;

    public RunAgentService(ChatClient.Builder chatClientBuilder, PromptCatalog prompts) {
        this.chatClient = chatClientBuilder.build();
        this.prompts = prompts;
    }

    public String chat(String message) {
        long startedAt = System.nanoTime();
        log.info("AI chat request started");
        try {
            String content = chatClient.prompt()
                    .system(prompts.chatSystemPrompt())
                    .user(message)
                    .call()
                    .content();
            log.info("AI chat request completed in {} ms", elapsedMillis(startedAt));
            return content == null ? "" : content;
        } catch (RuntimeException exception) {
            log.warn("AI chat request failed after {} ms", elapsedMillis(startedAt));
            throw new AiRequestException("Chat model invocation failed", exception);
        }
    }

    public Flux<String> stream(String message) {
        return Flux.defer(() -> {
            long startedAt = System.nanoTime();
            log.info("AI streaming request started");
            try {
                return chatClient.prompt()
                        .system(prompts.chatSystemPrompt())
                        .user(message)
                        .stream()
                        .content()
                        .doOnError(exception -> log.warn("AI streaming request failed after {} ms",
                                elapsedMillis(startedAt)))
                        .onErrorMap(exception -> exception instanceof AiRequestException
                                ? exception
                                : new AiRequestException("Streaming model invocation failed", exception))
                        .doFinally(signal -> log.info("AI streaming request finished with signal {} after {} ms",
                                signal, elapsedMillis(startedAt)));
            } catch (RuntimeException exception) {
                return Flux.error(new AiRequestException("Streaming model invocation failed", exception));
            }
        });
    }

    public RunningIntent parseRunningIntent(String question) {
        long startedAt = System.nanoTime();
        log.info("Structured output request started");
        try {
            RunningIntent parsed = chatClient.prompt()
                    .system(prompts.runningIntentSystemPrompt())
                    .user(question)
                    .call()
                    .entity(RunningIntent.class);
            RunningIntent intent = normalizeIntent(parsed, question);
            log.info("Structured output request completed in {} ms with intent {}",
                    elapsedMillis(startedAt), intent.intent());
            return intent;
        } catch (StructuredOutputException exception) {
            log.warn("Structured output validation failed after {} ms: {}",
                    elapsedMillis(startedAt), exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("Structured output parsing failed after {} ms", elapsedMillis(startedAt));
            throw new StructuredOutputException("Failed to convert model response to RunningIntent", exception);
        }
    }

    static RunningIntent normalizeIntent(RunningIntent parsed, String originalQuestion) {
        if (parsed == null) {
            throw new StructuredOutputException("Model returned an empty structured response");
        }
        if (parsed.startDate() != null && parsed.endDate() != null
                && parsed.startDate().isAfter(parsed.endDate())) {
            throw new StructuredOutputException("startDate must not be after endDate");
        }
        return parsed.withOriginalQuestion(originalQuestion);
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
