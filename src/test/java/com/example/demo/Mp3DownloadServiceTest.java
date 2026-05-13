package com.example.demo;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class Mp3DownloadServiceTest {

    @TempDir
    Path tempDir;

    private Mp3DownloadService service;

    @BeforeEach
    void setUp() throws IOException {
        service = new Mp3DownloadService(tempDir.toString(), 1, "TestAgent/1.0", 15, 60);
    }

    // -------------------------------------------------------------------------
    // filenameFromUrl
    // -------------------------------------------------------------------------
    @Nested
    class FilenameFromUrl {

        @Test
        void normalUrl_returnsLastPathSegment() {
            assertThat(service.filenameFromUrl("https://example.com/audio/track1.mp3"))
                    .isEqualTo("track1.mp3");
        }

        @Test
        void urlWithQueryString_stripsQuery() {
            assertThat(service.filenameFromUrl("https://example.com/audio/track1.mp3?v=123"))
                    .isEqualTo("track1.mp3");
        }

        @Test
        void urlEncodedFilename_decodesCorrectly() {
            assertThat(service.filenameFromUrl("https://example.com/%E9%A6%AC%E5%BE%90.mp3"))
                    .isEqualTo("馬徐.mp3");
        }

        @Test
        void urlWithNoPath_returnsFallbackHash() {
            String url = "https://example.com";
            String result = service.filenameFromUrl(url);
            assertThat(result).endsWith(".mp3");
            assertThat(result).startsWith("audio_");
        }

        @Test
        void illegalFilenameChars_replacedWithUnderscore() {
            assertThat(service.filenameFromUrl("https://example.com/a%3Ab.mp3"))
                    .isEqualTo("a_b.mp3");
        }
    }

    // -------------------------------------------------------------------------
    // isMp3
    // -------------------------------------------------------------------------
    @Nested
    class IsMp3 {

        @Test
        void mp3Url_returnsTrue() {
            assertThat(service.isMp3("https://example.com/audio.mp3")).isTrue();
        }

        @Test
        void mp3UpperCase_returnsTrue() {
            assertThat(service.isMp3("https://example.com/audio.MP3")).isTrue();
        }

        @Test
        void mp3WithQueryString_returnsTrue() {
            assertThat(service.isMp3("https://example.com/audio.mp3?token=abc")).isTrue();
        }

        @Test
        void nonMp3Extension_returnsFalse() {
            assertThat(service.isMp3("https://example.com/audio.ogg")).isFalse();
        }

        @Test
        void nullUrl_returnsFalse() {
            assertThat(service.isMp3(null)).isFalse();
        }

        @Test
        void blankUrl_returnsFalse() {
            assertThat(service.isMp3("   ")).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // extractVoiceLines
    // -------------------------------------------------------------------------
    @Nested
    class ExtractVoiceLines {

        @Test
        void wellFormedRow_returnsVoiceLine() {
            String html = """
                    <table><tr>
                      <th>Battle Start 1</th>
                      <td><p lang="ja">いくよ！</p><p>出發囉！</p></td>
                      <td><span class="MiniAudioPlayer"><source src="//cdn.example.com/b1.mp3"></span></td>
                    </tr></table>
                    """;
            List<Mp3DownloadService.VoiceLine> lines = service.extractVoiceLines(Jsoup.parse(html));
            assertThat(lines).hasSize(1);
            Mp3DownloadService.VoiceLine vl = lines.get(0);
            assertThat(vl.label()).isEqualTo("Battle Start 1");
            assertThat(vl.japanese()).isEqualTo("いくよ！");
            assertThat(vl.chinese()).isEqualTo("出發囉！");
            assertThat(vl.mp3Url()).isEqualTo("https://cdn.example.com/b1.mp3");
        }

        @Test
        void protocolRelativeSrc_getsPrefixed() {
            String html = """
                    <table><tr>
                      <th>Label</th>
                      <td><p lang="ja">JP</p></td>
                      <td><span class="MiniAudioPlayer"><source src="//cdn.example.com/a.mp3"></span></td>
                    </tr></table>
                    """;
            List<Mp3DownloadService.VoiceLine> lines = service.extractVoiceLines(Jsoup.parse(html));
            assertThat(lines.get(0).mp3Url()).isEqualTo("https://cdn.example.com/a.mp3");
        }

        @Test
        void rowWithoutTh_isSkipped() {
            String html = "<table><tr><td>no header</td></tr></table>";
            assertThat(service.extractVoiceLines(Jsoup.parse(html))).isEmpty();
        }

        @Test
        void rowWithoutJapaneseParagraph_isSkipped() {
            String html = """
                    <table><tr>
                      <th>Label</th>
                      <td><p>Chinese only, no lang attr</p></td>
                    </tr></table>
                    """;
            assertThat(service.extractVoiceLines(Jsoup.parse(html))).isEmpty();
        }

        @Test
        void rowWithoutAudioSource_isSkipped() {
            String html = """
                    <table><tr>
                      <th>Label</th>
                      <td><p lang="ja">JP text</p></td>
                      <td>no audio here</td>
                    </tr></table>
                    """;
            assertThat(service.extractVoiceLines(Jsoup.parse(html))).isEmpty();
        }

        @Test
        void multipleRows_allParsed() {
            String html = """
                    <table>
                      <tr>
                        <th>L1</th>
                        <td><p lang="ja">JP1</p></td>
                        <td><span class="MiniAudioPlayer"><source src="//example.com/1.mp3"></span></td>
                      </tr>
                      <tr>
                        <th>L2</th>
                        <td><p lang="ja">JP2</p></td>
                        <td><span class="MiniAudioPlayer"><source src="//example.com/2.mp3"></span></td>
                      </tr>
                    </table>
                    """;
            assertThat(service.extractVoiceLines(Jsoup.parse(html))).hasSize(2);
        }
    }

    // -------------------------------------------------------------------------
    // extractMp3Urls
    // -------------------------------------------------------------------------
    @Nested
    class ExtractMp3Urls {

        @Test
        void anchorHref_found() {
            String html = "<a href=\"https://example.com/track.mp3\">dl</a>";
            Document doc = Jsoup.parse(html, "https://example.com");
            assertThat(service.extractMp3Urls(doc, "https://example.com"))
                    .contains("https://example.com/track.mp3");
        }

        @Test
        void sourceTag_found() {
            String html = "<audio><source src=\"https://example.com/track.mp3\"></audio>";
            Document doc = Jsoup.parse(html, "https://example.com");
            assertThat(service.extractMp3Urls(doc, "https://example.com"))
                    .contains("https://example.com/track.mp3");
        }

        @Test
        void audioSrcAttribute_found() {
            String html = "<audio src=\"https://example.com/track.mp3\"></audio>";
            Document doc = Jsoup.parse(html, "https://example.com");
            assertThat(service.extractMp3Urls(doc, "https://example.com"))
                    .contains("https://example.com/track.mp3");
        }

        @Test
        void protocolRelativeInlineText_expandedToHttps() {
            String html = "<p>//example.com/audio.mp3</p>";
            Document doc = Jsoup.parse(html, "https://example.com");
            assertThat(service.extractMp3Urls(doc, "https://example.com"))
                    .contains("https://example.com/audio.mp3");
        }

        @Test
        void nonMp3Links_notIncluded() {
            String html = "<a href=\"https://example.com/image.png\">img</a>";
            Document doc = Jsoup.parse(html, "https://example.com");
            assertThat(service.extractMp3Urls(doc, "https://example.com")).isEmpty();
        }

        @Test
        void duplicateUrls_deduped() {
            String html = """
                    <a href="https://example.com/t.mp3">1</a>
                    <a href="https://example.com/t.mp3">2</a>
                    """;
            Document doc = Jsoup.parse(html, "https://example.com");
            assertThat(service.extractMp3Urls(doc, "https://example.com"))
                    .containsOnlyOnce("https://example.com/t.mp3");
        }
    }

    // -------------------------------------------------------------------------
    // countResults
    // -------------------------------------------------------------------------
    @Nested
    class CountResults {

        @Test
        void mixedStatuses_countedCorrectly() {
            List<Mp3DownloadService.FileResult> results = List.of(
                    new Mp3DownloadService.FileResult("u1", "/p1", "downloaded", null, 1000L),
                    new Mp3DownloadService.FileResult("u2", "/p2", "downloaded", null, 2000L),
                    new Mp3DownloadService.FileResult("u3", "/p3", "skipped",    null,  500L),
                    new Mp3DownloadService.FileResult("u4", null,  "failed",  "err",    0L)
            );
            int[] counts = service.countResults(results);
            assertThat(counts[0]).isEqualTo(2); // downloaded
            assertThat(counts[1]).isEqualTo(1); // skipped
            assertThat(counts[2]).isEqualTo(1); // failed
        }

        @Test
        void emptyList_allZero() {
            assertThat(service.countResults(List.of())).containsExactly(0, 0, 0);
        }

        @Test
        void unknownStatus_countedAsFailed() {
            List<Mp3DownloadService.FileResult> results = List.of(
                    new Mp3DownloadService.FileResult("u", null, "unknown-status", null, 0L)
            );
            int[] counts = service.countResults(results);
            assertThat(counts[2]).isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // buildByFilenameMap
    // -------------------------------------------------------------------------
    @Nested
    class BuildByFilenameMap {

        @Test
        void keysAreDecodedFilenames() {
            List<Mp3DownloadService.VoiceLine> lines = List.of(
                    new Mp3DownloadService.VoiceLine("L", "JP", "CN", "https://example.com/track1.mp3")
            );
            assertThat(service.buildByFilenameMap(lines)).containsKey("track1.mp3");
        }

        @Test
        void insertionOrderPreserved() {
            List<Mp3DownloadService.VoiceLine> lines = List.of(
                    new Mp3DownloadService.VoiceLine("A", "", "", "https://example.com/a.mp3"),
                    new Mp3DownloadService.VoiceLine("B", "", "", "https://example.com/b.mp3"),
                    new Mp3DownloadService.VoiceLine("C", "", "", "https://example.com/c.mp3")
            );
            assertThat(service.buildByFilenameMap(lines).keySet())
                    .containsExactly("a.mp3", "b.mp3", "c.mp3");
        }

        @Test
        void emptyList_returnsEmptyMap() {
            assertThat(service.buildByFilenameMap(List.of())).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // collectMp3Urls
    // -------------------------------------------------------------------------
    @Nested
    class CollectMp3Urls {

        @Test
        void voiceLineUrlsAreFirst() {
            List<Mp3DownloadService.VoiceLine> lines = List.of(
                    new Mp3DownloadService.VoiceLine("L", "J", "C", "https://example.com/voice.mp3")
            );
            String html = "<a href=\"https://example.com/other.mp3\">x</a>";
            Document doc = Jsoup.parse(html, "https://example.com");
            Set<String> urls = service.collectMp3Urls(lines, doc, "https://example.com");
            assertThat(urls.iterator().next()).isEqualTo("https://example.com/voice.mp3");
        }

        @Test
        void duplicatesAcrossVoiceLinesAndDom_deduped() {
            String sharedUrl = "https://example.com/shared.mp3";
            List<Mp3DownloadService.VoiceLine> lines = List.of(
                    new Mp3DownloadService.VoiceLine("L", "J", "C", sharedUrl)
            );
            String html = "<a href=\"" + sharedUrl + "\">x</a>";
            Document doc = Jsoup.parse(html, "https://example.com");
            Set<String> urls = service.collectMp3Urls(lines, doc, "https://example.com");
            assertThat(urls).containsOnlyOnce(sharedUrl);
        }

        @Test
        void domOnlyMp3_included() {
            List<Mp3DownloadService.VoiceLine> lines = List.of();
            String html = "<a href=\"https://example.com/extra.mp3\">x</a>";
            Document doc = Jsoup.parse(html, "https://example.com");
            assertThat(service.collectMp3Urls(lines, doc, "https://example.com"))
                    .contains("https://example.com/extra.mp3");
        }
    }

    // -------------------------------------------------------------------------
    // writeLinesTxt
    // -------------------------------------------------------------------------
    @Nested
    class WriteLinesTxt {

        @Test
        void writesFileToDownloadFolder() throws IOException {
            Map<String, Mp3DownloadService.VoiceLine> map = new LinkedHashMap<>();
            map.put("b1.mp3", new Mp3DownloadService.VoiceLine("Battle Start 1", "いくよ！", "出發囉！", "https://x/b1.mp3"));
            service.writeLinesTxt(map);
            assertThat(tempDir.resolve("lines.txt")).exists();
        }

        @Test
        void contentContainsAllFields() throws IOException {
            Map<String, Mp3DownloadService.VoiceLine> map = new LinkedHashMap<>();
            map.put("b1.mp3", new Mp3DownloadService.VoiceLine("Battle Start 1", "いくよ！", "出發囉！", "https://x/b1.mp3"));
            service.writeLinesTxt(map);
            String content = Files.readString(tempDir.resolve("lines.txt"));
            assertThat(content).contains("b1.mp3");
            assertThat(content).contains("Battle Start 1");
            assertThat(content).contains("いくよ！");
            assertThat(content).contains("出發囉！");
        }

        @Test
        void multipleEntries_allWritten() throws IOException {
            Map<String, Mp3DownloadService.VoiceLine> map = new LinkedHashMap<>();
            map.put("a.mp3", new Mp3DownloadService.VoiceLine("L1", "JP1", "CN1", "https://x/a.mp3"));
            map.put("b.mp3", new Mp3DownloadService.VoiceLine("L2", "JP2", "CN2", "https://x/b.mp3"));
            service.writeLinesTxt(map);
            String content = Files.readString(tempDir.resolve("lines.txt"));
            assertThat(content).contains("a.mp3").contains("b.mp3");
        }
    }
}
