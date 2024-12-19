package com.example.studentsystem;

import com.example.studentsystem.service.AppInstallerVerticle;
import io.vertx.core.Vertx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StudentSystemApplication {

	@Autowired
	private AppInstallerVerticle appInstallerVerticle;

	public static void main(String[] args) {
		SpringApplication.run(StudentSystemApplication.class, args);
	}

	@Autowired
	public void deployVerticle() {
		Vertx vertx = Vertx.vertx();
		vertx.deployVerticle(appInstallerVerticle, result -> {
			if (result.succeeded()) {
				System.out.println("AppInstallerVerticle deployed successfully!");
			} else {
				System.err.println("Failed to deploy AppInstallerVerticle: " + result.cause());
			}
		});
	}
}
