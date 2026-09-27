package cn.watsonzhu.runagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

class MemoryConfigTest {
    private final MemoryConfig config = new MemoryConfig();

    @Test
    void conversationsAreIsolatedAndCanBeCleared() {
        var memory = config.chatMemory(config.chatMemoryRepository(), new MemoryProperties(4));
        assertThat(memory.get("missing")).isEmpty();
        memory.add("A", new UserMessage("first"));
        memory.add("A", new AssistantMessage("answer"));
        assertThat(memory.get("A")).hasSize(2);
        assertThat(memory.get("B")).isEmpty();
        memory.clear("A");
        assertThat(memory.get("A")).isEmpty();
    }

    @Test
    void windowEvictsWholeOldTurn() {
        var memory = config.chatMemory(config.chatMemoryRepository(), new MemoryProperties(3));
        memory.add("A", new UserMessage("one"));
        memory.add("A", new AssistantMessage("answer one"));
        memory.add("A", new UserMessage("two"));
        memory.add("A", new AssistantMessage("answer two"));
        assertThat(memory.get("A")).hasSize(2);
        assertThat(memory.get("A").getFirst()).isInstanceOf(UserMessage.class);
        assertThat(memory.get("A").getFirst().getText()).isEqualTo("two");
    }

    @Test
    void configurationHasBounds() {
        assertThatThrownBy(() -> new MemoryProperties(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryProperties(201)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new MemoryProperties(20).maxMessages()).isEqualTo(20);
    }
}
