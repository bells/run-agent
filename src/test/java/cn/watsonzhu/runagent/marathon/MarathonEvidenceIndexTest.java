package cn.watsonzhu.runagent.marathon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class MarathonEvidenceIndexTest {
    private static final String CORPUS = """
            {"schemaVersion":1,"documents":[
              {"id":"alpha-course-2026","raceId":"alpha","year":2026,"kind":"COURSE_GUIDE",
               "title":"甲城马拉松赛道指南","publisher":"甲城组委会","url":"https://example.com/alpha/course",
               "publishedAt":"2026-03-02","aliases":["甲城马拉松","甲马"],
               "paragraphs":["二十公里后进入连续爬升路段。","终点设于滨河公园。"]},
              {"id":"alpha-course-2025","raceId":"alpha","year":2025,"kind":"COURSE_GUIDE",
               "title":"甲城马拉松赛道指南","publisher":"甲城组委会","url":"https://example.com/alpha/old-course",
               "publishedAt":"2025-03-02","aliases":["甲城马拉松","甲马"],
               "paragraphs":["十公里后进入连续爬升路段。"]},
              {"id":"beta-interview","raceId":"beta","year":2026,"kind":"INTERVIEW",
               "title":"乙城马拉松赛后采访","publisher":"乙城组委会","url":"https://example.com/beta/interview",
               "publishedAt":"2026-04-12","aliases":["乙城马拉松","Beta Marathon"],
               "paragraphs":["冠军在采访中谈到了连续爬升路段。","The winner described the headwind near the finish."]}]}
            """;

    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void retrievesParagraphAndPublishesTypedCitation(@TempDir Path dir) throws Exception {
        var index = load(dir, CORPUS);

        assertThat(index.available()).isTrue();
        assertThat(index.search("２０２６ 年甲马哪里有爬升？")).singleElement().satisfies(match -> {
            assertThat(match.text()).isEqualTo("二十公里后进入连续爬升路段。");
            assertThat(match.citation()).isEqualTo(new MarathonEvidenceIndex.Citation("alpha-course-2026",
                    "alpha", 2026, MarathonEvidenceIndex.Kind.COURSE_GUIDE, "甲城马拉松赛道指南", "甲城组委会",
                    "https://example.com/alpha/course", LocalDate.of(2026, 3, 2), null));
        });
    }

    @Test
    void scopesAResolvedRaceAndEditionAndDoesNotSubstituteMissingYears(@TempDir Path dir) throws Exception {
        var index = load(dir, CORPUS);

        assertThat(index.search("连续爬升", "alpha", 2025)).singleElement()
                .satisfies(match -> assertThat(match.text()).contains("十公里"));
        assertThat(index.search("2024 年甲城马拉松哪里有爬升？")).isEmpty();
        assertThat(index.search("连续爬升", "unlisted", 2026)).isEmpty();
    }

    @Test
    void findsEnglishTermsAndRequiresTopicalOverlap(@TempDir Path dir) throws Exception {
        var index = load(dir, CORPUS);

        assertThat(index.search("Headwind at the 2026 Beta Marathon?")).singleElement()
                .satisfies(match -> assertThat(match.citation().documentId()).isEqualTo("beta-interview"));
        assertThat(index.search("2026 年甲城马拉松芯片故障原因")).isEmpty();
        assertThat(index.search("2026 年甲城马拉松")).isEmpty();
    }

    @Test
    void extractsOnlyCompleteRaceAliasesAndPrefersTheLongest(@TempDir Path dir) throws Exception {
        var index = load(dir, CORPUS.replace("\"Beta Marathon\"", "\"Beta Marathon\",\"甲城马拉松半程\""));

        assertThat(index.raceIdsInQuestion("2026年甲城马拉松半程的爬升")).containsExactly("beta");
        assertThat(index.raceIdsInQuestion("甲马和Beta Marathon的赛道")).containsExactlyInAnyOrder("alpha", "beta");
        assertThat(index.raceIdsInQuestion("Unknown Marathoner")).isEmpty();
        assertThat(index.raceIdsInQuestion("LongBeta Marathoner")).isEmpty();
    }

    @Test
    void failsClosedForUnsafeProvenanceOrMalformedCorpus(@TempDir Path dir) throws Exception {
        assertThat(new MarathonEvidenceIndex(json, null).available()).isFalse();
        assertThat(new MarathonEvidenceIndex(json, dir.resolve("missing.json")).available()).isFalse();
        assertThat(load(dir, CORPUS.replace("https://example.com", "https://secret@example.com")).available())
                .isFalse();
        assertThat(load(dir, CORPUS.replace("https://example.com", "http://example.com")).available()).isFalse();
        assertThat(load(dir, CORPUS.replace("alpha-course-2025", "alpha-course-2026")).available()).isFalse();
        assertThat(load(dir, CORPUS.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
                .search("爬升")).isEmpty();
        assertThat(load(dir, CORPUS.replace("\"schemaVersion\":1", "\"schemaVersion\":2,\"schemaVersion\":1"))
                .available()).isFalse();
        assertThat(load(dir, CORPUS + "{}").available()).isFalse();
    }

    @Test
    void rejectsOversizedCorpusAndReturnsBoundedRelevantPassages(@TempDir Path dir) throws Exception {
        String longerCourse = CORPUS.replace("\"终点设于滨河公园。\"",
                """
                "爬升路段设有单独指引。","爬升后经过松林。","坡顶设有爬升结束标记。",
                "终点前还有爬升路段。","需要留意第二段爬升。"
                """);

        assertThat(load(dir, longerCourse).search("2026 年甲城马拉松爬升")).hasSize(4)
                .allSatisfy(match -> assertThat(match.text()).contains("爬升"));
        assertThat(load(dir, CORPUS + " ".repeat(1024 * 1024)).available()).isFalse();
    }

    private MarathonEvidenceIndex load(Path dir, String corpus) throws Exception {
        return new MarathonEvidenceIndex(json, Files.writeString(dir.resolve("evidence.json"), corpus));
    }
}
