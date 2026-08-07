package com.docedit.samples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docedit.payload.request.DocumentCreate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end workflow over the full HTTP stack using a real sample document:
 * create -> search -> redline -> search again (re-indexed) -> accept.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SampleWorkflowTest {

    private static final String HARRY_POTTER = load("harry_potter.txt");

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

    @Test
    void editingASampleDocumentChangesWhatSearchFinds() throws Exception {
        final String id = create(HARRY_POTTER, "Harry Potter");
        assertEquals(1, searchCount("Grunnings"));   // present in the original text

        final int at = HARRY_POTTER.indexOf("Grunnings");
        redline(id, at, at + "Grunnings".length(), "Acme");

        assertEquals(1, searchCount("Acme"));         // the accepted text is re-indexed on write
        assertEquals(0, searchCount("Grunnings"));    // the struck word is no longer searchable
    }

    @Test
    void acceptingAllChangesOnASampleDocumentFinalizesTheText() throws Exception {
        final String id = create(HARRY_POTTER, "Harry Potter");
        final int at = HARRY_POTTER.indexOf("Grunnings");
        redline(id, at, at + "Grunnings".length(), "Acme");

        final String body = mockMvc.perform(post("/documents/" + id + "/accept-all"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final JsonNode doc = objectMapper.readTree(body);

        assertTrue(doc.get("acceptedText").asText().contains("a firm called Acme, which made drills"));
        for (final JsonNode segment : doc.get("segments")) {
            assertEquals("UNCHANGED", segment.get("type").asText());
        }
    }

    private String create(final String text, final String title) throws Exception {
        final String body = objectMapper.writeValueAsString(new DocumentCreate(text, title));
        final String response = mockMvc.perform(post("/documents")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private void redline(final String id, final int start, final int end, final String replacement)
            throws Exception {
        final String body = "{\"changes\":[{\"range\":{\"start\":" + start + ",\"end\":" + end
                + "},\"replacement\":\"" + replacement + "\"}]}";
        mockMvc.perform(patch("/documents/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private int searchCount(final String query) throws Exception {
        final String response = mockMvc.perform(get("/documents/search").param("q", query))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("results").size();
    }

    private static String load(final String name) {
        try (InputStream in = SampleWorkflowTest.class.getResourceAsStream("/samples/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
