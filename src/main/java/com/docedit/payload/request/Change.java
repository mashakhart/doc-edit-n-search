package com.docedit.payload.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import javax.annotation.Nullable;

/**
 * A single edit: replace the characters in range with replacement, applied as a
 * redline. A null or empty replacement is a pure deletion; a zero-width range is
 * a pure insertion. operation defaults to "replace", the only operation for now.
 */
public record Change(
        @Nullable String operation,
        @NotNull @Valid Range range,
        @Nullable String replacement) {
}
