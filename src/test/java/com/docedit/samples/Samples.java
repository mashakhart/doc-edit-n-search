package com.docedit.samples;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Loads the sample documents stored under src/test/resources/samples. */
final class Samples {

    static final String HARRY_POTTER = load("harry_potter.txt");
    static final String THE_STRANGER = load("the_stranger.txt");

    private Samples() {
    }

    static String load(final String name) {
        try (InputStream in = Samples.class.getResourceAsStream("/samples/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
