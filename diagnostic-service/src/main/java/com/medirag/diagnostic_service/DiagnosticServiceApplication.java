package com.medirag.diagnostic_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;


@SpringBootApplication
@EnableAsync
public class DiagnosticServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(DiagnosticServiceApplication.class, args);
	}

}
