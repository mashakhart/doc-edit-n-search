package com.docedit.payload.request;

import jakarta.validation.constraints.Min;

/**
 * A position-based edit target: the half-open character interval from start
 * (inclusive) to end (exclusive). A zero-width range (start == end) inserts.
 */
public record Range(
        @Min(0) int start,
        @Min(0) int end) {
}
