package cn.watsonzhu.runagent.marathon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import static cn.watsonzhu.runagent.marathon.MarathonData.Distance;
import static cn.watsonzhu.runagent.marathon.MarathonData.Podium;

final class MarathonPodiumStore {
    private static final Logger log = LoggerFactory.getLogger(MarathonPodiumStore.class);
    private static final int MAX_BYTES = 32 * 1024;
    private static final int MAX_FILES_PER_SCAN = 512;

    private final ObjectMapper json;
    private final Path directory;
    private final Map<Key, Podium> seeds;

    MarathonPodiumStore(ObjectMapper json, Path directory) {
        this.json = json;
        this.directory = directory;
        try (var input = new ClassPathResource("marathon/marathon_podiums.json").getInputStream()) {
            var bundled = json.readValue(input, Podium[].class);
            var entries = new HashMap<Key, Podium>();
            for (var podium : bundled) {
                if (entries.putIfAbsent(Key.of(podium), podium) != null) {
                    throw new IllegalArgumentException("Duplicate bundled podium");
                }
            }
            this.seeds = Map.copyOf(entries);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to load bundled marathon results", exception);
        }
    }

    Optional<Podium> find(Key key) {
        return read(key).or(() -> Optional.ofNullable(seeds.get(key)));
    }

    List<Podium> all() {
        var entries = new HashMap<>(seeds);
        try (var files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .limit(MAX_FILES_PER_SCAN).forEach(file -> Key.fromFilename(file.getFileName().toString())
                            .ifPresent(key -> addToIndex(entries, key)));
        } catch (NoSuchFileException ignored) {
            // The bundled entries also cover a fresh install.
        } catch (IOException | RuntimeException exception) {
            log.warn("marathon_podium_index status=UNAVAILABLE");
        }
        return List.copyOf(entries.values());
    }

    private void addToIndex(Map<Key, Podium> entries, Key key) {
        try {
            read(key).ifPresent(podium -> entries.put(key, podium));
        } catch (Unavailable exception) {
            entries.remove(key);
            log.warn("marathon_podium_entry status=INVALID");
        }
    }

    void save(Podium podium) {
        Path stage = null;
        try {
            byte[] bytes = json.writeValueAsBytes(podium);
            if (bytes.length > MAX_BYTES) throw new Unavailable();
            Files.createDirectories(directory);
            stage = Files.createTempFile(directory, ".podium-", ".tmp");
            Files.write(stage, bytes);
            Files.move(stage, directory.resolve(Key.of(podium).filename()),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            throw new Unavailable();
        } finally {
            if (stage != null) {
                try {
                    Files.deleteIfExists(stage);
                } catch (IOException ignored) {
                    log.warn("marathon_podium_temporary_file status=CLEANUP_FAILED");
                }
            }
        }
    }

    private Optional<Podium> read(Key key) {
        try (var input = Files.newInputStream(directory.resolve(key.filename()))) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new Unavailable();
            var podium = json.readValue(bytes, Podium.class);
            if (podium == null || !key.equals(Key.of(podium))) throw new Unavailable();
            return Optional.of(podium);
        } catch (NoSuchFileException exception) {
            return Optional.empty();
        } catch (IOException | RuntimeException exception) {
            throw new Unavailable();
        }
    }

    record Key(String eventId, Distance distance) {
        Key {
            if (!eventId.matches("[a-z0-9][a-z0-9_-]{0,84}") || distance == null) {
                throw new IllegalArgumentException("Invalid marathon result key");
            }
        }

        static Key of(Podium podium) {
            return new Key(podium.eventId(), podium.distance());
        }

        static Optional<Key> fromFilename(String name) {
            var parts = name.substring(0, name.length() - 5).split("\\.", -1);
            if (parts.length != 2) return Optional.empty();
            try {
                return Optional.of(new Key(parts[0], Distance.valueOf(parts[1].toUpperCase(Locale.ROOT))));
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }

        String filename() {
            return eventId + "." + distance.name().toLowerCase(Locale.ROOT) + ".json";
        }
    }

    static final class Unavailable extends RuntimeException {}
}
