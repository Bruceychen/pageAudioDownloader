package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "mp3.download.url=")
class DemoApplicationTests {

	@Test
	void contextLoads() {
	}

}
