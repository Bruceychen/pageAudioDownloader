package com.example.demo;

import jakarta.annotation.PreDestroy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class Mp3DownloadService {

    private static final Pattern MP3_URL_PATTERN = Pattern.compile(
            "(?:https?:)?//[^\\s\"'<>()\\\\]+?\\.mp3(?:\\?[^\\s\"'<>()\\\\]*)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FILENAME_ILLEGAL_CHARS = Pattern.compile("[\\\\/:*?\"<>|]");

    private final Path downloadFolder;
    private final String userAgent;
    private final Duration readTimeout;

    private final HttpClient httpClient;
    private final ExecutorService executor;

    public Mp3DownloadService(
            @Value("${mp3.download.folder:download}") String folder,
            @Value("${mp3.download.threads:4}") int threads,
            @Value("${mp3.download.user-agent}") String userAgent,
            @Value("${mp3.download.connect-timeout-seconds:15}") int connectTimeoutSeconds,
            @Value("${mp3.download.read-timeout-seconds:60}") int readTimeoutSeconds) throws IOException {
        this.downloadFolder = Paths.get(folder).toAbsolutePath();
        this.userAgent = userAgent;
        this.readTimeout = Duration.ofSeconds(readTimeoutSeconds);
        Duration connectTimeout = Duration.ofSeconds(connectTimeoutSeconds);
        int threadCount = Math.max(1, threads);
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(connectTimeout)
                .build();
        this.executor = Executors.newFixedThreadPool(threadCount);
        Files.createDirectories(this.downloadFolder);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public DownloadSummary processPage(String pageUrl) throws IOException {
        Document doc = fetchPage(pageUrl);
        List<VoiceLine> voiceLines = extractVoiceLines(doc);
        Set<String> mp3Urls = collectMp3Urls(voiceLines, doc, pageUrl);
        Map<String, VoiceLine> byFilename = buildByFilenameMap(voiceLines);
        List<FileResult> results = runDownloads(mp3Urls);
        int[] counts = countResults(results);

        if (!byFilename.isEmpty()) {
            writeLinesTxt(byFilename);
        }

        return new DownloadSummary(
                pageUrl,
                downloadFolder.toString(),
                mp3Urls.size(),
                counts[0],
                counts[1],
                counts[2],
                results,
                voiceLines);
    }

    private Document fetchPage(String pageUrl) throws IOException {
        return Jsoup.connect(pageUrl)
                .userAgent(userAgent)
                .timeout((int) readTimeout.toMillis())
                .followRedirects(true)
                .get();
    }

    Set<String> collectMp3Urls(List<VoiceLine> voiceLines, Document doc, String pageUrl) {
        Set<String> mp3Urls = new LinkedHashSet<>();
        for (VoiceLine vl : voiceLines) {
            mp3Urls.add(vl.mp3Url());
        }
        mp3Urls.addAll(extractMp3Urls(doc, pageUrl));
        return mp3Urls;
    }

    Map<String, VoiceLine> buildByFilenameMap(List<VoiceLine> voiceLines) {
        Map<String, VoiceLine> byFilename = new LinkedHashMap<>();
        for (VoiceLine vl : voiceLines) {
            byFilename.put(filenameFromUrl(vl.mp3Url()), vl);
        }
        return byFilename;
    }

    private List<FileResult> runDownloads(Set<String> mp3Urls) {
        List<Future<FileResult>> futures = new ArrayList<>(mp3Urls.size());
        for (String mp3Url : mp3Urls) {
            futures.add(executor.submit(() -> downloadOne(mp3Url)));
        }
        List<FileResult> results = new ArrayList<>(futures.size());
        for (Future<FileResult> f : futures) {
            try {
                results.add(f.get());
            } catch (Exception e) {
                results.add(new FileResult(null, null, "failed", e.getMessage(), 0L));
            }
        }
        return results;
    }

    int[] countResults(List<FileResult> results) {
        int downloaded = 0, skipped = 0, failed = 0;
        for (FileResult r : results) {
            switch (r.status()) {
                case "downloaded" -> downloaded++;
                case "skipped" -> skipped++;
                default -> failed++;
            }
        }
        return new int[]{downloaded, skipped, failed};
    }

    /**
     * Parses each table row that has the structure:
     *   th  (label text)
     *   td > p[lang=ja]  (Japanese)
     *   td > p (no lang attr)  (Chinese)
     *   td span.MiniAudioPlayer source[src]  (mp3 url)
     */
    List<VoiceLine> extractVoiceLines(Document doc) {
        List<VoiceLine> lines = new ArrayList<>();
        for (Element row : doc.select("tr")) {
            Element th = row.selectFirst("th");
            if (th == null) continue;

            String label = th.text().trim();
            if (label.isEmpty()) continue;

            // Japanese text
            Element jpElem = row.selectFirst("td p[lang=ja]");
            if (jpElem == null) continue;
            String japanese = jpElem.text().trim();

            // Chinese text: first <p> without lang attribute inside the same td
            Element textTd = jpElem.parent();
            String chinese = "";
            for (Element p : textTd.select("p")) {
                if (p.attr("lang").isEmpty()) {
                    chinese = p.text().trim();
                    break;
                }
            }

            // MP3 URL from MiniAudioPlayer source
            Element source = row.selectFirst("span.MiniAudioPlayer source[src]");
            if (source == null) continue;
            String mp3Url = source.attr("src");
            if (mp3Url.startsWith("//")) mp3Url = "https:" + mp3Url;
            if (mp3Url.isEmpty()) continue;

            lines.add(new VoiceLine(label, japanese, chinese, mp3Url));
        }
        return lines;
    }

    void writeLinesTxt(Map<String, VoiceLine> byFilename) throws IOException {
        Path out = downloadFolder.resolve("lines.txt");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, VoiceLine> entry : byFilename.entrySet()) {
            String filename = entry.getKey();
            VoiceLine vl = entry.getValue();
            sb.append(filename).append('\n');
            sb.append(vl.label()).append('\n');
            sb.append('\n');
            sb.append(vl.japanese()).append('\n');
            sb.append('\n');
            sb.append(vl.chinese()).append('\n');
            sb.append('\n');
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    Set<String> extractMp3Urls(Document doc, String pageUrl) {
        Set<String> urls = new LinkedHashSet<>();

        for (Element a : doc.select("a[href]")) {
            String abs = a.absUrl("href");
            if (isMp3(abs)) urls.add(abs);
        }
        for (Element s : doc.select("source[src], audio[src]")) {
            String abs = s.absUrl("src");
            if (isMp3(abs)) urls.add(abs);
        }

        URI base = URI.create(pageUrl);
        Matcher m = MP3_URL_PATTERN.matcher(doc.outerHtml());
        while (m.find()) {
            String raw = m.group();
            if (raw.startsWith("//")) raw = base.getScheme() + ":" + raw;
            urls.add(raw);
        }

        return urls;
    }

    boolean isMp3(String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase();
        int q = lower.indexOf('?');
        String path = q >= 0 ? lower.substring(0, q) : lower;
        return path.endsWith(".mp3");
    }

    private FileResult downloadOne(String mp3Url) {
        String filename;
        try {
            filename = filenameFromUrl(mp3Url);
        } catch (Exception e) {
            return new FileResult(mp3Url, null, "failed", "bad-url: " + e.getMessage(), 0L);
        }

        Path target = downloadFolder.resolve(filename);
        if (Files.exists(target)) {
            return new FileResult(mp3Url, target.toString(), "skipped", null, sizeOf(target));
        }

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(mp3Url))
                    .header("User-Agent", userAgent)
                    .header("Accept", "*/*")
                    .timeout(readTimeout)
                    .GET()
                    .build();

            HttpResponse<Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(target));
            if (resp.statusCode() / 100 != 2) {
                try { Files.deleteIfExists(target); } catch (IOException ignored) {}
                return new FileResult(mp3Url, null, "failed", "HTTP " + resp.statusCode(), 0L);
            }
            return new FileResult(mp3Url, target.toString(), "downloaded", null, sizeOf(target));
        } catch (Exception e) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            return new FileResult(mp3Url, null, "failed", e.getMessage(), 0L);
        }
    }

    private long sizeOf(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0L; }
    }

    String filenameFromUrl(String url) {
        URI uri = URI.create(url);
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            return "audio_" + Math.abs(url.hashCode()) + ".mp3";
        }
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        String decoded = URLDecoder.decode(last, StandardCharsets.UTF_8);
        String safe = FILENAME_ILLEGAL_CHARS.matcher(decoded).replaceAll("_").trim();
        if (safe.isEmpty()) safe = "audio_" + Math.abs(url.hashCode()) + ".mp3";
        return safe;
    }

    public record VoiceLine(String label, String japanese, String chinese, String mp3Url) {}

    public record FileResult(String url, String savedPath, String status, String error, long sizeBytes) {}

    public record DownloadSummary(
            String pageUrl,
            String downloadFolder,
            int found,
            int downloaded,
            int skipped,
            int failed,
            List<FileResult> results,
            List<VoiceLine> voiceLines) {}
}