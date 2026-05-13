package com.example.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Application {

	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(Application.class, args);
		int exitCode = SpringApplication.exit(context);
		System.exit(exitCode);
	}

	@Bean
	CommandLineRunner runOnce(
			Mp3DownloadService service,
			@Value("${mp3.download.url:}") String url) {
		return args -> {
			if (url == null || url.isBlank()) {
				System.err.println("ERROR: mp3.download.url is not set in application.properties");
				return;
			}

			System.out.println("==========================================");
			System.out.println("MP3 downloader -- scanning page:");
			System.out.println("  " + url);
			System.out.println("==========================================");

			var summary = service.processPage(url);

			System.out.println();
			System.out.println("=== Summary ===");
			System.out.println("Page         : " + summary.pageUrl());
			System.out.println("Folder       : " + summary.downloadFolder());
			System.out.println("Found URLs   : " + summary.found());
			System.out.println("Downloaded   : " + summary.downloaded());
			System.out.println("Skipped      : " + summary.skipped());
			System.out.println("Failed       : " + summary.failed());
			System.out.println("Voice lines  : " + summary.voiceLines().size()
					+ (!summary.voiceLines().isEmpty() ? "  (lines.txt written)" : ""));

			if (!summary.results().isEmpty()) {
				System.out.println();
				System.out.println("=== Files ===");
				summary.results().forEach(r -> {
					String line = "[" + r.status() + "] "
							+ (r.savedPath() != null ? r.savedPath() : r.url());
					if (r.error() != null) line += "  -- " + r.error();
					System.out.println(line);
				});
			}
		};
	}
}
