package com.docedit.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docedit.AbstractApiTest;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for the search endpoint. A BeforeEach clears the store (and
 * thus the index) so each test searches only its own documents.
 */
class SearchControllerTest extends AbstractApiTest {

    @Test
    void matchesByTextContentWithSnippet() throws Exception {
        createDocument("The parties agree to the contract.", "MSA");
        createDocument("A lease for the property.", "Lease");
        mockMvc.perform(get("/documents/search").param("q", "contract"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].title").value("MSA"))
                .andExpect(jsonPath("$.results[0].snippet", containsString("contract")));
    }

    @Test
    void matchingIsOrderIndependent() throws Exception {
        createDocument("alpha beta gamma", "Doc");
        mockMvc.perform(get("/documents/search").param("q", "gamma alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1));
    }

    @Test
    void matchesByTitle() throws Exception {
        createDocument("body text here", "Zephyrandum");
        mockMvc.perform(get("/documents/search").param("q", "zephyrandum"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1));
    }

    @Test
    void noSharedWordReturnsNoResults() throws Exception {
        createDocument("hello world", "Doc");
        mockMvc.perform(get("/documents/search").param("q", "zzzznomatch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(0));
    }

    @Test
    void partialWordMatches() throws Exception {
        createDocument("I ate an apple today", "Fruit");
        mockMvc.perform(get("/documents/search").param("q", "appl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].title").value("Fruit"));
    }
}
