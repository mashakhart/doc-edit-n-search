package com.docedit.payload.response;

/** The JSON error body returned for every 4xx/5xx: { error, code }. */
public record ErrorResponse(String error, int code) {
}
