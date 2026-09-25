package org.healthtg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HealthTgApplication {
    public static void main(String[] args) {
        SpringApplication.run(HealthTgApplication.class, args);
    }
}
