package com.docedit.payload.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** The PATCH request body: a non-empty batch of changes applied atomically. */
public record ChangeRequest(
        @NotEmpty List<@Valid Change> changes) {
}
