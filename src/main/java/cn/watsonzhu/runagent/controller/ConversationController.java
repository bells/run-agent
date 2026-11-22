package cn.watsonzhu.runagent.controller;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import cn.watsonzhu.runagent.controller.PrivateChatController.PrivateChatRequest;
import cn.watsonzhu.runagent.controller.PrivateChatController.PrivateChatResponse;
import cn.watsonzhu.runagent.conversation.ConversationStore;
import cn.watsonzhu.runagent.conversation.ConversationStore.State;
import cn.watsonzhu.runagent.conversation.ConversationStore.Turn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static cn.watsonzhu.runagent.controller.PrivateApiAccess.error;

@RestController
@RequestMapping("/api/v1/conversations")
@ConditionalOnProperty(name = {"run-agent.private-chat.enabled", "run-agent.conversations.enabled"}, havingValue = "true")
public class ConversationController {
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private final ConversationStore store;
    private final PrivateApiAccess access;
    private final PrivateChatController chat;
    private final Semaphore writing = new Semaphore(1);

    public ConversationController(ConversationStore store, PrivateChatController chat,
                                  @Value("${run-agent.private-chat.token:}") String token) {
        this.store = store;
        this.chat = chat;
        this.access = new PrivateApiAccess(token);
    }

    @GetMapping
    public Mono<ResponseEntity<?>> list(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String requestId = UUID.randomUUID().toString();
        if (!access.allows(authorization)) return Mono.just(PrivateApiAccess.unauthorized(requestId));
        return execute(requestId, () -> success(store.list()));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<?>> transcript(@PathVariable String id,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String requestId = UUID.randomUUID().toString();
        if (!access.allows(authorization)) return Mono.just(PrivateApiAccess.unauthorized(requestId));
        if (!ID.matcher(id).matches()) return Mono.just(missing(requestId));
        return execute(requestId, () -> {
            var turns = store.transcript(id);
            return turns.isEmpty() ? missing(requestId) : success(turns);
        });
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<?>> delete(@PathVariable String id,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String requestId = UUID.randomUUID().toString();
        if (!access.allows(authorization)) return Mono.just(PrivateApiAccess.unauthorized(requestId));
        if (!ID.matcher(id).matches()) return Mono.just(missing(requestId));
        return execute(requestId, () -> {
            store.delete(id);
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        });
    }

    @PostMapping(path = "/{id}/turns", consumes = "application/json")
    public Mono<ResponseEntity<?>> send(@PathVariable String id,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) Mono<JsonNode> body) {
        String requestId = UUID.randomUUID().toString();
        if (!access.allows(authorization)) return Mono.just(PrivateApiAccess.unauthorized(requestId));
        if (!ID.matcher(id).matches()) return Mono.just(missing(requestId));
        return body.flatMap(node -> {
                    var request = TurnRequest.parse(node);
                    return request == null ? Mono.just(invalid(requestId))
                            : execute(requestId, () -> saveTurn(requestId, id, request));
                })
                .switchIfEmpty(Mono.fromSupplier(() -> invalid(requestId)))
                .onErrorResume(ServerWebInputException.class, failure -> Mono.just(invalid(requestId)));
    }

    private ResponseEntity<?> saveTurn(String requestId, String id, TurnRequest request) {
        var replay = store.findTurn(id, request.turnId());
        if (replay != null) return replay(requestId, replay, request);
        if (!writing.tryAcquire()) return error(409, requestId, "CHAT_BUSY", "A conversation is already being answered.");
        try {
            replay = store.findTurn(id, request.turnId());
            if (replay != null) return replay(requestId, replay, request);
            State state = store.state(id);
            if (state == State.DELETED || (state == State.MISSING && !request.create())) return missing(requestId);
            if (state == State.ACTIVE && request.create()) {
                return error(409, requestId, "CONVERSATION_EXISTS", "This conversation already exists.");
            }
            var chatRequest = new PrivateChatRequest(request.chat().message(), request.chat().context(), store.recent(id));
            var reply = chat.answer(requestId, chatRequest, true);
            if (!(reply.getBody() instanceof PrivateChatResponse answer)) return reply;
            var saved = new Turn(request.turnId(), chatRequest.message(), answer.content(), answer.evidence());
            return store.append(id, request.create(), saved) ? success(saved) : missing(requestId);
        } finally {
            writing.release();
        }
    }

    private static ResponseEntity<?> replay(String requestId, Turn saved, TurnRequest request) {
        return saved.question().equals(request.chat().message()) ? success(saved)
                : error(409, requestId, "TURN_CONFLICT", "The turn ID was already used for another message.");
    }

    private static Mono<ResponseEntity<?>> execute(String requestId, Callable<ResponseEntity<?>> operation) {
        return PrivateChatController.execute(requestId, operation)
                .onErrorResume(ConversationStore.StorageException.class, failure ->
                        Mono.just(error(503, requestId, "CONVERSATION_STORAGE_UNAVAILABLE", "Conversation storage is unavailable.")));
    }

    private static ResponseEntity<?> success(Object value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    private static ResponseEntity<?> missing(String requestId) {
        return error(404, requestId, "CONVERSATION_NOT_FOUND", "The conversation was not found.");
    }

    private static ResponseEntity<?> invalid(String requestId) {
        return error(400, requestId, "INVALID_REQUEST", "Enter a valid question and turn ID.");
    }

    private record TurnRequest(String turnId, boolean create, PrivateChatRequest chat) {
        static TurnRequest parse(JsonNode node) {
            if (!(node instanceof ObjectNode input) || !input.path("clientTurnId").isString()
                    || !ID.matcher(input.path("clientTurnId").asString()).matches()
                    || !input.path("create").isBoolean() || input.has("history")) return null;
            var body = input.deepCopy();
            body.remove("clientTurnId");
            body.remove("create");
            var chat = PrivateChatRequest.from(body);
            return chat == null ? null : new TurnRequest(input.path("clientTurnId").asString(), input.path("create").asBoolean(), chat);
        }
    }
}
