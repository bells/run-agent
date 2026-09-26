package cn.watsonzhu.runagent.agent;

import java.time.Duration;

import cn.watsonzhu.runagent.exception.AiRequestException;
import cn.watsonzhu.runagent.exception.StructuredOutputException;
import cn.watsonzhu.runagent.model.RunningIntent;
import cn.watsonzhu.runagent.prompt.PromptCatalog;
import cn.watsonzhu.runagent.tool.RunningTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** 集中管理模型调用；Controller 只处理 HTTP，Tool 和数据读取也各有独立职责。 */
@Service
public class RunAgentService {

    private static final Logger log = LoggerFactory.getLogger(RunAgentService.class);

    private final ChatClient chatClient;
    private final PromptCatalog prompts;
    private final RunningTools runningTools;

    public RunAgentService(ChatClient.Builder chatClientBuilder, PromptCatalog prompts, RunningTools runningTools) {
        this.chatClient = chatClientBuilder.build();
        this.prompts = prompts;
        this.runningTools = runningTools;
    }

    public String chat(String message) {
        long startedAt = System.nanoTime();
        log.info("AI chat request started");
        try {
            // .tools() 提供工具定义；模型只会请求调用，Spring AI 的 ToolCallingAdvisor 才会执行 Java 方法并再次请求模型。
            String content = chatClient.prompt()
                    .system(prompts.chatSystemPrompt())
                    .user(message)
                    .tools(runningTools)
                    .call()
                    .content();
            log.info("AI final response completed in {} ms", elapsedMillis(startedAt));
            return content == null ? "" : content;
        } catch (RuntimeException exception) {
            log.warn("AI chat request failed after {} ms", elapsedMillis(startedAt));
            throw new AiRequestException("Chat model invocation failed", exception);
        }
    }

    public Flux<String> stream(String message) {
        // defer 保证真正订阅时才开始模型请求，也让客户端断开时的 cancel 沿 Flux 链路传播。
        return Flux.defer(() -> {
            long startedAt = System.nanoTime();
            log.info("AI streaming request started");
            try {
                return chatClient.prompt()
                        .system(prompts.chatSystemPrompt())
                        .user(message)
                        // Streaming 与普通 Chat 暴露同一个 Tool，结果仍由 Spring AI 交回模型生成最终文本。
                        .tools(runningTools)
                        .stream()
                        .content()
                        .doOnComplete(() -> log.info("AI streaming final response completed in {} ms",
                                elapsedMillis(startedAt)))
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
            // entity() 解决“返回什么 Java 结构”；这里不提供 Tool，避免把意图识别与外部数据查询混在一起。
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
        // 模型输出是不可信输入：校验日期，并用真实请求覆盖模型可能改写的原问题。
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
