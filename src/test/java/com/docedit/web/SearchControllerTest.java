package com.docedit.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docedit.payload.request.DocumentCreate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests for the search endpoint. A BeforeEach clears the store (and
 * thus the index) so each test searches only its own documents.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearExistingDocuments() throws Exception {
        final String listBody = mockMvc.perform(get("/documents"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (final JsonNode document : objectMapper.readTree(listBody)) {
            mockMvc.perform(delete("/documents/" + document.get("id").asText()))
                    .andExpect(status().isNoContent());
        }
    }

    private void create(final String text, final String title) throws Exception {
        final String body = objectMapper.writeValueAsString(new DocumentCreate(text, title));
        mockMvc.perform(post("/documents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void matchesByTextContentWithSnippet() throws Exception {
        create("The parties agree to the contract.", "MSA");
        create("A lease for the property.", "Lease");
        mockMvc.perform(get("/documents/search").param("q", "contract"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].title").value("MSA"))
                .andExpect(jsonPath("$.results[0].snippet", containsString("contract")));
    }

    @Test
    void matchingIsOrderIndependent() throws Exception {
        create("alpha beta gamma", "Doc");
        mockMvc.perform(get("/documents/search").param("q", "gamma alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1));
    }

    @Test
    void matchesByTitle() throws Exception {
        create("body text here", "Zephyrandum");
        mockMvc.perform(get("/documents/search").param("q", "zephyrandum"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1));
    }

    @Test
    void noSharedWordReturnsNoResults() throws Exception {
        create("hello world", "Doc");
        mockMvc.perform(get("/documents/search").param("q", "zzzznomatch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(0));
    }

    @Test
    void partialWordMatches() throws Exception {
        create("I ate an apple today", "Fruit");
        mockMvc.perform(get("/documents/search").param("q", "appl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].title").value("Fruit"));
    }
}
