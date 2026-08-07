package com.docedit.samples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docedit.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * End-to-end workflow over the full HTTP stack using a real sample document:
 * create -> search -> redline -> search again (re-indexed) -> accept.
 */
class SampleWorkflowTest extends AbstractApiTest {

    @Test
    void editingASampleDocumentChangesWhatSearchFinds() throws Exception {
        final String id = createDocument(Samples.HARRY_POTTER, "Harry Potter");
        assertEquals(1, searchCount("Grunnings"));   // present in the original text

        final int at = Samples.HARRY_POTTER.indexOf("Grunnings");
        redline(id, at, at + "Grunnings".length(), "Acme");

        assertEquals(1, searchCount("Acme"));         // the accepted text is re-indexed on write
        assertEquals(0, searchCount("Grunnings"));    // the struck word is no longer searchable
    }

    @Test
    void acceptingAllChangesOnASampleDocumentFinalizesTheText() throws Exception {
        final String id = createDocument(Samples.HARRY_POTTER, "Harry Potter");
        final int at = Samples.HARRY_POTTER.indexOf("Grunnings");
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
}
