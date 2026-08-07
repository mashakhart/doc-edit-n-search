package com.docedit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docedit.payload.request.DocumentCreate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base for end-to-end API tests: wires MockMvc + Jackson and clears the store
 * (and thus the search index) before each test, so tests act only on the
 * documents they create.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractApiTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    protected void clearExistingDocuments() throws Exception {
        final String listBody = mockMvc.perform(get("/documents"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (final JsonNode document : objectMapper.readTree(listBody)) {
            mockMvc.perform(delete("/documents/" + document.get("id").asText()))
                    .andExpect(status().isNoContent());
        }
    }

    /** Creates a document via the API and returns its id. */
    protected String createDocument(final String text, final String title) throws Exception {
        final String body = objectMapper.writeValueAsString(new DocumentCreate(text, title));
        final String response = mockMvc.perform(post("/documents")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }
}
