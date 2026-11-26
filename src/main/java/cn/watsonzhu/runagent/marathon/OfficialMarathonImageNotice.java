package cn.watsonzhu.runagent.marathon;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import static cn.watsonzhu.runagent.marathon.MarathonData.*;

final class OfficialMarathonImageNotice {
    private static final Logger log = LoggerFactory.getLogger(OfficialMarathonImageNotice.class);
    private static final int MIN_CONFIDENCE = 60;
    private static final int MIN_NAME_CONFIDENCE = 40;
    private static final Pattern NAME = Pattern.compile(
            "[\\p{IsHan}]{2,16}|[A-Za-z][A-Za-z .’'\\-]{1,118}[A-Za-z.]");
    private static final Comparator<Word> LEFT_TO_RIGHT = Comparator.comparingInt(Word::x);

    private final List<Notice> notices;
    private final Recognizer recognize;

    OfficialMarathonImageNotice(List<Notice> notices, Recognizer recognize) {
        this.notices = List.copyOf(notices);
        this.recognize = recognize;
    }

    static OfficialMarathonImageNotice bundled(ObjectMapper json) {
        try (var input = new ClassPathResource("marathon/marathon_image_notices.json").getInputStream()) {
            return new OfficialMarathonImageNotice(List.of(json.readValue(input, Notice[].class)),
                    new TesseractMarathonOcr()::recognize);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load marathon image notices", exception);
        }
    }

    Optional<Notice> find(Event event, Distance distance) {
        if (event == null || event.status() != EventStatus.CONFIRMED && event.status() != EventStatus.COMPLETED) {
            return Optional.empty();
        }
        return notices.stream().filter(notice -> notice.eventId().equals(event.eventId())
                && notice.raceDate().equals(event.raceDate()) && notice.distance() == distance).findFirst();
    }

    static Set<String> linkedImages(byte[] html, Charset charset, Notice notice) throws IOException {
        var page = Jsoup.parse(new ByteArrayInputStream(html), charset == null ? null : charset.name(),
                notice.page().toString());
        page.select("script,style,nav,footer,aside,template,noscript,[hidden],[aria-hidden=true]").remove();
        return page.select("img[src]").eachAttr("abs:src").stream().collect(Collectors.toUnmodifiableSet());
    }

    static boolean verifiedAttachment(byte[] png, Attachment attachment) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png))
                    .equals(attachment.sha256());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    List<Rank> read(byte[] png, Attachment attachment, Board board, long deadline) {
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            var readers = ImageIO.getImageReadersByFormatName("png");
            if (!readers.hasNext()) return List.of();
            var reader = readers.next();
            BufferedImage image;
            try {
                reader.setInput(stream, true, true);
                if (reader.getWidth(0) != attachment.width() || reader.getHeight(0) != attachment.height()) {
                    return List.of();
                }
                var parameters = reader.getDefaultReadParam();
                // Decode the heading, column headers and podium only. Some notices are over 12,000 pixels tall.
                parameters.setSourceRegion(new Rectangle(0, board.top(), attachment.width(), board.height()));
                image = reader.read(0, parameters);
            } finally {
                reader.dispose();
            }
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) return List.of();
            return verifiedRanks(recognize.read(image, board.subCategory(), deadline), image.getWidth());
        } catch (IOException | RuntimeException exception) {
            log.debug("marathon_image_notice status=IMAGE_UNAVAILABLE");
            return List.of();
        }
    }

    private static List<Rank> verifiedRanks(List<Word> recognized, int width) {
        if (recognized.isEmpty() || recognized.size() > 500) return List.of();
        var words = recognized.stream().sorted(Comparator.comparingInt(Word::y)).toList();
        var lines = new ArrayList<List<Word>>();
        for (Word word : words) {
            var last = lines.isEmpty() ? List.<Word>of() : lines.getLast();
            if (last.isEmpty() || !sameRow(last.getFirst(), word)) {
                lines.add(new ArrayList<>(List.of(word)));
            } else {
                last.add(word);
            }
        }
        int headerRow = -1;
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            String rankHeader = compact(text(column(line, width, Column.RANK), MIN_CONFIDENCE));
            if (Set.of("名次", "排名").contains(rankHeader)
                    && "姓名".equals(compact(text(column(line, width, Column.NAME), MIN_CONFIDENCE)))
                    && "枪声成绩".equals(compact(text(column(line, width, Column.TIME), MIN_CONFIDENCE)))) {
                headerRow = i;
                break;
            }
        }
        if (headerRow < 0 || lines.size() < headerRow + 4) return List.of();

        var ranks = new ArrayList<Rank>(3);
        for (int rank = 1; rank <= 3; rank++) {
            var row = lines.get(headerRow + rank);
            String place = compact(text(column(row, width, Column.RANK), MIN_CONFIDENCE));
            String name = text(column(row, width, Column.NAME), MIN_NAME_CONFIDENCE);
            if (name.codePoints().allMatch(character -> Character.isWhitespace(character)
                    || Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN)) {
                name = compact(name);
            }
            String time = OfficialMarathonEvidence.finishTime(
                    compact(text(column(row, width, Column.TIME), MIN_CONFIDENCE)));
            if (!Integer.toString(rank).equals(place) || !NAME.matcher(name).matches() || time == null
                    || !ranks.isEmpty() && (time.compareTo(ranks.getLast().finishTime()) < 0
                            || duplicateAthlete(ranks, name))) return List.of();
            ranks.add(new Rank(rank, name, null, time, null));
        }
        return List.copyOf(ranks);
    }

    private static boolean duplicateAthlete(List<Rank> ranks, String name) {
        return ranks.stream().anyMatch(rank -> rank.athleteName().equalsIgnoreCase(name));
    }

    private static boolean sameRow(Word first, Word second) {
        return Math.abs(first.y() - second.y()) <= Math.max(first.height(), second.height()) * 2 / 3;
    }

    private static List<Word> column(List<Word> words, int width, Column column) {
        return words.stream().filter(word -> column.contains((double) word.x() / width)).toList();
    }

    private static String text(List<Word> words, int confidence) {
        if (words.stream().anyMatch(word -> word.confidence() < confidence)) return "";
        return words.stream().sorted(LEFT_TO_RIGHT).map(Word::text).collect(Collectors.joining(" "));
    }

    private static String compact(String value) {
        return value.replaceAll("\\s+", "");
    }

    // The organizer's current poster has separate rank, bib, athlete and gun-time columns.
    private enum Column {
        RANK(0.02, 0.15), NAME(0.30, 0.73), TIME(0.76, 0.97);

        private final double min;
        private final double max;

        Column(double min, double max) {
            this.min = min;
            this.max = max;
        }

        boolean contains(double position) {
            return position >= min && position < max;
        }
    }

    record Notice(String eventId, LocalDate raceDate, Distance distance, URI page,
                  String organization, List<Attachment> attachments) {
        Notice {
            if (eventId == null || raceDate == null || distance == null || !secureUrl(page) || organization == null
                    || attachments == null || attachments.isEmpty() || attachments.size() > 4) {
                throw new IllegalArgumentException("Invalid marathon image notice");
            }
            attachments = List.copyOf(attachments);
        }
    }

    record Attachment(URI url, String sha256, int width, int height, List<Board> boards) {
        Attachment {
            if (!secureUrl(url) || sha256 == null || !sha256.matches("[a-f0-9]{64}")
                    || width <= 0 || width > 4096 || height <= 0 || height > 18000
                    || boards == null || boards.isEmpty() || boards.size() > 4
                    || boards.stream().anyMatch(board -> board.top() + board.height() > height)) {
                throw new IllegalArgumentException("Invalid marathon image attachment");
            }
            boards = List.copyOf(boards);
        }
    }

    record Board(Category category, SubCategory subCategory, int top, int height) {
        Board {
            if (category == null || subCategory == null || top < 0 || top > 18000 || height <= 0 || height > 1700) {
                throw new IllegalArgumentException("Invalid marathon image crop");
            }
        }
    }

    record Word(int x, int y, int height, double confidence, String text) {}

    @FunctionalInterface
    interface Recognizer {
        List<Word> read(BufferedImage image, SubCategory subgroup, long deadline);
    }

    private static boolean secureUrl(URI uri) {
        return uri != null && "https".equals(uri.getScheme()) && uri.getHost() != null
                && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }
}
