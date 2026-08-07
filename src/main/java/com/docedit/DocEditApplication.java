package com.docedit;

import com.docedit.store.DocumentStore;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
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

    /**
     * Single background thread for search re-indexing, so the O(n) tokenizing work
     * on a write happens off the request thread. Single-threaded, so tasks run in
     * submission order (they are submitted under the store's per-document lock),
     * keeping the index consistent with the latest document version. Tests replace
     * this with a synchronous executor for deterministic search.
     */
    @Bean("searchIndexExecutor")
    Executor searchIndexExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "search-index");
            thread.setDaemon(true);
            return thread;
        });
    }
}
