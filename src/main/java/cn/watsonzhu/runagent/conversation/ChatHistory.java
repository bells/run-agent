package cn.watsonzhu.runagent.conversation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.JsonNode;

public final class ChatHistory {
    public static final int MAX_TURNS = 4;
    private static final int MAX_MESSAGE_BYTES = 750;

    private ChatHistory() {}

    public record Entry(String role, String content) {
        Message asMessage() {
            return "user".equals(role) ? new UserMessage(content) : new AssistantMessage(content);
        }
    }

    public static List<Entry> parse(JsonNode value) {
        if (value.isMissingNode()) return List.of();
        if (!value.isArray() || value.size() > 2 * MAX_TURNS || value.size() % 2 != 0) {
            throw new IllegalArgumentException("Invalid chat history");
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode item : value) {
            String role = entries.size() % 2 == 0 ? "user" : "assistant";
            if (!item.isObject() || item.size() != 2 || !item.path("role").isString()
                    || !role.equals(item.path("role").asString()) || !item.path("content").isString()) {
                throw new IllegalArgumentException("Invalid chat history message");
            }
            String content = item.path("content").asString();
            if (content.isBlank() || content.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
                throw new IllegalArgumentException("Invalid chat history message length");
            }
            entries.add(new Entry(role, content));
        }
        return List.copyOf(entries);
    }

    public static List<Message> messages(List<Entry> history) {
        return history.stream().map(Entry::asMessage).toList();
    }

    public static String excerpt(String content) {
        int offset = 0;
        int bytes = 0;
        while (offset < content.length()) {
            int point = content.codePointAt(offset);
            bytes += point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
            if (bytes > MAX_MESSAGE_BYTES) break;
            offset += Character.charCount(point);
        }
        return content.substring(0, offset);
    }
}
