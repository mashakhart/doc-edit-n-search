package com.docedit;

import com.docedit.store.DocumentStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class DocEditApplication {

    public static void main(final String[] args) {
        SpringApplication.run(DocEditApplication.class, args);
    }

    /** Seeds an empty "Untitled" document so a freshly started app always has one to open. */
    @Bean
    CommandLineRunner seedUntitledDocument(final DocumentStore store) {
        return args -> store.create("", "Untitled");
    }
}
