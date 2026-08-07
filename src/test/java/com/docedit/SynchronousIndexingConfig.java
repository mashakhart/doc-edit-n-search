package com.docedit;

import java.util.concurrent.Executor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Replaces the background re-indexing executor with a synchronous one (runs each
 * task on the calling thread) so that after a write returns, the search index is
 * already up to date. This keeps the API tests deterministic without sleeps or
 * polling. Overrides the production bean of the same name.
 */
@TestConfiguration
public class SynchronousIndexingConfig {

    @Bean("searchIndexExecutor")
    Executor searchIndexExecutor() {
        return Runnable::run;
    }
}
