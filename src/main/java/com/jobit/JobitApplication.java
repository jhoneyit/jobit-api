package com.jobit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JobitApplication {

	public static void main(String[] args) {
		SpringApplication.run(JobitApplication.class, args);
	}

}
