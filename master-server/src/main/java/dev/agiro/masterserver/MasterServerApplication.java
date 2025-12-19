package dev.agiro.masterserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties
@SpringBootApplication
public class MasterServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MasterServerApplication.class, args);
    }

}
