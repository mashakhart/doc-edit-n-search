package com.docedit.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docedit.AbstractApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * End-to-end API tests over the running web stack. A BeforeEach clears the store
 * so every test starts from a clean slate, using only the public API.
 */
class DocumentControllerTest extends AbstractApiTest {

    @Test
    void createReturns201WithVersionAndEtag() throws Exception {
        mockMvc.perform(post("/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"the parties agree","title":"Doc"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.acceptedText").value("the parties agree"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
    }

    @Test
    void createWithoutTextDefaultsToEmpty() throws Exception {
        mockMvc.perform(post("/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.acceptedText").value(""))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void getReturnsDocument() throws Exception {
        final String id = createDocument("hello world", "Doc");
        mockMvc.perform(get("/documents/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedText").value("hello world"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
    }

    @Test
    void listIncludesCreatedDocuments() throws Exception {
        createDocument("first", "List-A");
        createDocument("second", "List-B");
        mockMvc.perform(get("/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].title", hasItems("List-A", "List-B")));
    }

    @Test
    void getMissingReturns404WithErrorBody() throws Exception {
        mockMvc.perform(get("/documents/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.error", containsString("not found")));
    }

    @Test
    void patchProducesRedlineAndBumpsVersion() throws Exception {
        final String id = createDocument("the quick brown fox", "Doc");
        mockMvc.perform(patch("/documents/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changes":[{"range":{"start":4,"end":9},"replacement":"slow"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedText").value("the slow brown fox"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(jsonPath("$.segments[?(@.type=='DELETED')].text", hasItem("quick")))
                .andExpect(jsonPath("$.segments[?(@.type=='INSERTED')].text", hasItem("slow")));
    }

    @Test
    void patchAppliesBulkChanges() throws Exception {
        final String id = createDocument("one two three", "Doc");
        mockMvc.perform(patch("/documents/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changes":[
                                  {"range":{"start":0,"end":3},"replacement":"1"},
                                  {"range":{"start":8,"end":13},"replacement":"3"}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedText").value("1 two 3"));
    }

    @Test
    void patchWithOutOfBoundsRangeReturns422() throws Exception {
        final String id = createDocument("abc", "Doc");
        mockMvc.perform(patch("/documents/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changes":[{"range":{"start":0,"end":99},"replacement":"q"}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));
    }

    @Test
    void patchWithMissingRangeReturns422() throws Exception {
        final String id = createDocument("abc", "Doc");
        mockMvc.perform(patch("/documents/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changes":[{"replacement":"q"}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));
    }

    @Test
    void patchWithEmptyChangesReturns422() throws Exception {
        final String id = createDocument("abc", "Doc");
        mockMvc.perform(patch("/documents/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changes":[]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));
    }

    @Test
    void patchOnMissingDocumentReturns404() throws Exception {
        mockMvc.perform(patch("/documents/does-not-exist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changes":[{"range":{"start":0,"end":1},"replacement":"b"}]}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void staleIfMatchIsRejectedWith412() throws Exception {
        final String id = createDocument("hello world", "Doc");
        mockMvc.perform(patch("/documents/" + id)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changes":[{"range":{"start":0,"end":5},"replacement":"hi"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(patch("/documents/" + id)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changes":[{"range":{"start":6,"end":11},"replacement":"earth"}]}"""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value(412));
    }

    @Test
    void editsApplyAsAnOrderedStreamAndBumpVersionOnce() throws Exception {
        final String id = createDocument("cat", "Doc");
        // Two keystrokes: append "s" (now "cats"), then append "!" at index 4 — the
        // second edit is only in range because the first already ran. One version bump.
        mockMvc.perform(post("/documents/" + id + "/edits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changes":[
                                  {"range":{"start":3,"end":3},"replacement":"s"},
                                  {"range":{"start":4,"end":4},"replacement":"!"}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedText").value("cats!"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""));
    }

    @Test
    void editsAllowALaterEditThatAtomicBatchWouldReject() throws Exception {
        // Against the 3-char original, range [4,4) is out of bounds — the atomic
        // PATCH would 422. As a sequential stream the first edit grows the text first.
        final String id = createDocument("abc", "Doc");
        mockMvc.perform(post("/documents/" + id + "/edits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changes":[
                                  {"range":{"start":3,"end":3},"replacement":"d"},
                                  {"range":{"start":4,"end":4},"replacement":"e"}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedText").value("abcde"));
    }

    @Test
    void editsCanLayerAnEditOnAPriorRedline() throws Exception {
        final String id = createDocument("the quick brown fox", "Doc");
        // Redline quick->slow, then (on the flattened "the quickslow brown fox")
        // strike " brown" at [13,19); accepted result drops both struck spans.
        mockMvc.perform(post("/documents/" + id + "/edits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changes":[
                                  {"range":{"start":4,"end":9},"replacement":"slow"},
                                  {"range":{"start":13,"end":19},"replacement":""}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedText").value("the slow fox"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void editsRejectStaleIfMatchWith412() throws Exception {
        final String id = createDocument("hello", "Doc"); // v1
        mockMvc.perform(post("/documents/" + id + "/edits")
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changes":[{"range":{"start":5,"end":5},"replacement":"!"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(post("/documents/" + id + "/edits")
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changes":[{"range":{"start":0,"end":1},"replacement":"J"}]}"""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value(412));
    }

    @Test
    void editsOnMissingDocumentReturns404() throws Exception {
        mockMvc.perform(post("/documents/does-not-exist/edits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changes":[{"range":{"start":0,"end":0},"replacement":"x"}]}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void deleteRemovesDocument() throws Exception {
        final String id = createDocument("disposable", "Doc");
        mockMvc.perform(delete("/documents/" + id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/documents/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void deleteMissingDocumentReturns404() throws Exception {
        mockMvc.perform(delete("/documents/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    private String redlineQuickToSlow(final String title) throws Exception {
        final String id = createDocument("the quick brown fox", title);
        mockMvc.perform(patch("/documents/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changes":[{"range":{"start":4,"end":9},"replacement":"slow"}]}"""))
                .andExpect(status().isOk());
        return id;
    }

    @Test
    void acceptFinalizesTheTargetedChange() throws Exception {
        final String id = redlineQuickToSlow("Doc");
        // The change spans struck "quick" plus inserted "slow": flattened [4,13).
        mockMvc.perform(post("/documents/" + id + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"start":4,"end":13}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedText").value("the slow brown fox"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.segments.length()").value(1))
                .andExpect(jsonPath("$.segments[0].type").value("UNCHANGED"));
    }

    @Test
    void rejectRevertsTheTargetedChange() throws Exception {
        final String id = redlineQuickToSlow("Doc");
        mockMvc.perform(post("/documents/" + id + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"start":4,"end":13}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedText").value("the quick brown fox"))
                .andExpect(jsonPath("$.segments[0].type").value("UNCHANGED"));
    }

    @Test
    void acceptOnMissingDocumentReturns404() throws Exception {
        mockMvc.perform(post("/documents/does-not-exist/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"start":0,"end":1}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void acceptAllFinalizesEveryChange() throws Exception {
        final String id = redlineQuickToSlow("Doc");
        mockMvc.perform(post("/documents/" + id + "/accept-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedText").value("the slow brown fox"))
                .andExpect(jsonPath("$.segments.length()").value(1))
                .andExpect(jsonPath("$.segments[0].type").value("UNCHANGED"));
    }

    @Test
    void rejectAllRevertsEveryChange() throws Exception {
        final String id = redlineQuickToSlow("Doc");
        mockMvc.perform(post("/documents/" + id + "/reject-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedText").value("the quick brown fox"));
    }

    @Test
    void rejectOnMissingDocumentReturns404() throws Exception {
        mockMvc.perform(post("/documents/does-not-exist/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"start":0,"end":1}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void acceptWithOutOfBoundsRangeReturns422() throws Exception {
        final String id = createDocument("abc", "Doc");
        mockMvc.perform(post("/documents/" + id + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"start":0,"end":99}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));
    }

    @Test
    void staleIfMatchIsRejectedOnAcceptWith412() throws Exception {
        final String id = redlineQuickToSlow("Doc"); // create (v1) + patch (v2)
        mockMvc.perform(post("/documents/" + id + "/accept")
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"start":4,"end":13}"""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value(412));
    }
}
