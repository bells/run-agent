package cn.watsonzhu.runagent.marathon;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import static cn.watsonzhu.runagent.marathon.MarathonData.SubCategory;
import static cn.watsonzhu.runagent.marathon.OfficialMarathonImageNotice.Word;

final class TesseractMarathonOcr {
    private static final Logger log = LoggerFactory.getLogger(TesseractMarathonOcr.class);
    private static final long MAX_TSV_BYTES = 128 * 1024;

    private final String script = loadScript();
    private final Semaphore permit = new Semaphore(1);

    List<Word> recognize(BufferedImage crop, SubCategory subgroup, long deadline) {
        if (Thread.currentThread().isInterrupted() || deadline <= System.nanoTime() || !permit.tryAcquire()) {
            return List.of();
        }
        Path directory = null;
        Process process = null;
        try {
            directory = Files.createTempDirectory("marathon-ocr-");
            var image = directory.resolve("crop.png");
            var output = directory.resolve("recognized.tsv");
            if (!ImageIO.write(crop, "png", image.toFile()) || deadline <= System.nanoTime()) return List.of();

            var command = new ProcessBuilder("python3", "-I", "-c", script, subgroup.name());
            process = command.redirectInput(image.toFile()).redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                log.debug("marathon_image_notice status=OCR_TIMEOUT");
                return List.of();
            }
            if (process.exitValue() != 0 || Files.size(output) > MAX_TSV_BYTES) {
                log.debug("marathon_image_notice status=OCR_UNAVAILABLE");
                return List.of();
            }
            return parse(Files.readAllLines(output));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException exception) {
            log.debug("marathon_image_notice status=OCR_UNAVAILABLE");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (directory != null) {
                try {
                    Files.deleteIfExists(directory.resolve("recognized.tsv"));
                    Files.deleteIfExists(directory.resolve("crop.png"));
                    Files.deleteIfExists(directory);
                } catch (IOException exception) {
                    log.debug("marathon_image_notice status=OCR_CLEANUP_FAILED");
                }
            }
            permit.release();
        }
        return List.of();
    }

    private static List<Word> parse(List<String> lines) {
        var words = new ArrayList<Word>();
        for (String line : lines) {
            var fields = line.split("\t", 5);
            if (fields.length != 5 || fields[4].isBlank()) continue;
            try {
                int x = Integer.parseInt(fields[0]);
                int y = Integer.parseInt(fields[1]);
                int height = Integer.parseInt(fields[2]);
                double confidence = Double.parseDouble(fields[3]);
                if (x >= 0 && y >= 0 && height > 0 && confidence >= 0 && confidence <= 100) {
                    words.add(new Word(x, y, height, confidence, fields[4].strip()));
                }
            } catch (NumberFormatException exception) {
                // Malformed OCR rows cannot provide evidence.
            }
        }
        return List.copyOf(words);
    }

    private static String loadScript() {
        try {
            return new ClassPathResource("marathon/official_image_ocr.py").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load marathon image OCR script", exception);
        }
    }
}
