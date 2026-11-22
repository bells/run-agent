package cn.watsonzhu.runagent.conversation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import cn.watsonzhu.runagent.evidence.RunningEvidenceService.Evidence;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = {"run-agent.private-chat.enabled", "run-agent.conversations.enabled"}, havingValue = "true")
public class ConversationStore {
    private final String url;
    private final ObjectMapper json;

    public ConversationStore(@Value("${run-agent.conversations.directory}") String directory, ObjectMapper json)
            throws IOException {
        this.json = json;
        Path root = Path.of(directory).toAbsolutePath();
        if (root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectories(root);
        }
        url = "jdbc:sqlite:" + root.resolve("conversations.sqlite");
        withConnection(connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS conversations (
                            id TEXT PRIMARY KEY, title TEXT, updated_at INTEGER NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS conversation_turns (
                            sequence INTEGER PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES conversations(id),
                            id TEXT NOT NULL, question TEXT NOT NULL, answer TEXT NOT NULL, evidence TEXT,
                            UNIQUE(conversation_id, id)
                        )
                        """);
                statement.executeUpdate("""
                        CREATE INDEX IF NOT EXISTS conversation_turn_order ON conversation_turns(conversation_id, sequence)
                        """);
            }
            return null;
        });
    }

    public List<Summary> list() {
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT id, title, updated_at FROM conversations WHERE title IS NOT NULL ORDER BY updated_at DESC, id
                    """); var rows = statement.executeQuery()) {
                List<Summary> summaries = new ArrayList<>();
                while (rows.next()) summaries.add(new Summary(rows.getString(1), rows.getString(2), rows.getLong(3)));
                return summaries;
            }
        });
    }

    public List<Turn> transcript(String id) {
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT t.id, t.question, t.answer, t.evidence FROM conversations c
                    JOIN conversation_turns t ON t.conversation_id = c.id
                    WHERE c.id = ? AND c.title IS NOT NULL ORDER BY t.sequence
                    """)) {
                statement.setString(1, id);
                return readTurns(statement.executeQuery());
            }
        });
    }

    public List<ChatHistory.Entry> recent(String id) {
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT question, answer FROM conversation_turns WHERE conversation_id = ?
                    ORDER BY sequence DESC LIMIT ?
                    """)) {
                statement.setString(1, id);
                statement.setInt(2, ChatHistory.MAX_TURNS);
                try (var rows = statement.executeQuery()) {
                    List<ChatHistory.Entry> messages = new ArrayList<>();
                    while (rows.next()) {
                        messages.addFirst(new ChatHistory.Entry("assistant", ChatHistory.excerpt(rows.getString(2))));
                        messages.addFirst(new ChatHistory.Entry("user", ChatHistory.excerpt(rows.getString(1))));
                    }
                    return messages;
                }
            }
        });
    }

    public Turn findTurn(String id, String turnId) {
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT id, question, answer, evidence FROM conversation_turns WHERE conversation_id = ? AND id = ?
                    """)) {
                statement.setString(1, id);
                statement.setString(2, turnId);
                var turns = readTurns(statement.executeQuery());
                return turns.isEmpty() ? null : turns.getFirst();
            }
        });
    }

    public State state(String id) {
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement("SELECT title FROM conversations WHERE id = ?")) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) return State.MISSING;
                    return rows.getString(1) == null ? State.DELETED : State.ACTIVE;
                }
            }
        });
    }

    public boolean append(String id, boolean create, Turn turn) {
        return withConnection(connection -> {
            connection.setAutoCommit(false);
            long now = Instant.now().toEpochMilli();
            if (create) {
                try (var statement = connection.prepareStatement("""
                        INSERT INTO conversations (id, title, updated_at) VALUES (?, ?, ?)
                        ON CONFLICT(id) DO NOTHING
                        """)) {
                    statement.setString(1, id);
                    String question = turn.question().strip().replaceAll("\\s+", " ");
                    statement.setString(2, question.substring(0, question.offsetByCodePoints(0,
                            Math.min(48, question.codePointCount(0, question.length())))));
                    statement.setLong(3, now);
                    if (statement.executeUpdate() == 0) return false;
                }
            } else {
                try (var statement = connection.prepareStatement("""
                        UPDATE conversations SET updated_at = ? WHERE id = ? AND title IS NOT NULL
                        """)) {
                    statement.setLong(1, now);
                    statement.setString(2, id);
                    if (statement.executeUpdate() == 0) return false;
                }
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO conversation_turns (conversation_id, id, question, answer, evidence) VALUES (?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, id);
                statement.setString(2, turn.id());
                statement.setString(3, turn.question());
                statement.setString(4, turn.answer());
                statement.setString(5, turn.evidence() == null ? null : json.writeValueAsString(turn.evidence()));
                statement.executeUpdate();
            }
            connection.commit();
            return true;
        });
    }

    public void delete(String id) {
        withConnection(connection -> {
            connection.setAutoCommit(false);
            // Keep only an ID after deletion so an old first-turn request cannot recreate the conversation.
            try (var statement = connection.prepareStatement("""
                    INSERT INTO conversations (id, title, updated_at) VALUES (?, NULL, 0)
                    ON CONFLICT(id) DO UPDATE SET title = NULL, updated_at = 0
                    """)) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("DELETE FROM conversation_turns WHERE conversation_id = ?")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
            connection.commit();
            return null;
        });
    }

    private List<Turn> readTurns(ResultSet rows) throws SQLException {
        try (rows) {
            List<Turn> turns = new ArrayList<>();
            while (rows.next()) {
                String evidence = rows.getString(4);
                turns.add(new Turn(rows.getString(1), rows.getString(2), rows.getString(3),
                        evidence == null ? null : json.readValue(evidence, Evidence.class)));
            }
            return turns;
        }
    }

    private <T> T withConnection(SqlOperation<T> operation) {
        try (var connection = DriverManager.getConnection(url);
             var settings = connection.createStatement()) {
            settings.execute("PRAGMA foreign_keys = ON");
            settings.execute("PRAGMA busy_timeout = 5000");
            settings.execute("PRAGMA secure_delete = ON");
            return operation.run(connection);
        } catch (SQLException exception) {
            throw new StorageException(exception);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run(Connection connection) throws SQLException;
    }

    public enum State { MISSING, ACTIVE, DELETED }

    public record Summary(String id, String title, long updatedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Turn(String id, String question, String answer, Evidence evidence) {}

    public static final class StorageException extends RuntimeException {
        private StorageException(SQLException cause) {
            super("Conversation storage is unavailable", cause);
        }
    }
}
