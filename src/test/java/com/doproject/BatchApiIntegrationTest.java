package com.doproject;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doproject.client.InferenceClient;
import com.doproject.security.ApiKeyAuthenticationFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class BatchApiIntegrationTest {
    private static final String API_KEY = "dev-key";

    @TempDir static Path workspace;
    @TempDir static Path jobData;

    @DynamicPropertySource
    static void configureWorkspace(DynamicPropertyRegistry registry) {
        registry.add("batch.input-directory", () -> workspace.toString());
        registry.add("persistence.directory", () -> jobData.toString());
        registry.add("webhook.allowed-hosts[0]", () -> "localhost");
        registry.add("app.api-key", () -> API_KEY);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean InferenceClient inferenceClient;

    @Test
    void uploadsBatchAndReturnsCompiledResults() throws Exception {
        when(inferenceClient.evaluate(anyString(), anyString())).thenAnswer(invocation -> "output:" + invocation.getArgument(0));
        MockMultipartFile file = new MockMultipartFile("file", "prompts.json", MediaType.APPLICATION_JSON_VALUE,
                "[\"first\",\"second\"]".getBytes());

        MvcResult submission = mockMvc.perform(withApiKey(multipart("/job").file(file).param("route", "cheap")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isString())
                .andReturn();
        String jobId = objectMapper.readValue(submission.getResponse().getContentAsString(), Map.class).get("jobId").toString();

        waitForStatus(jobId, "COMPLETED");

        mockMvc.perform(withApiKey(get("/job/{id}/status", jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completed").value(2))
                .andExpect(jsonPath("$.failed").value(0));
        mockMvc.perform(withApiKey(get("/job/{id}/download", jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].output").value("output:first"))
                .andExpect(jsonPath("$[1].output").value("output:second"));
    }

    @Test
    void rejectsInvalidPromptArray() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "prompts.json", MediaType.APPLICATION_JSON_VALUE,
                "[\"\"]".getBytes());

        MvcResult submission = mockMvc.perform(withApiKey(multipart("/job").file(file)))
                .andExpect(status().isAccepted())
                .andReturn();
        String jobId = objectMapper.readValue(submission.getResponse().getContentAsString(), Map.class).get("jobId").toString();

        waitForStatus(jobId, "FAILED");

        mockMvc.perform(withApiKey(get("/job/{id}/status", jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void readsBatchFromConfiguredLocalWorkspace() throws Exception {
        when(inferenceClient.evaluate(anyString(), anyString())).thenAnswer(invocation -> "output:" + invocation.getArgument(0));
        Files.writeString(workspace.resolve("local-prompts.json"), "[\"local\"]");

        MvcResult submission = mockMvc.perform(withApiKey(post("/job/local")
                        .param("file", "local-prompts.json")
                        .param("route", "cheap")))
                .andExpect(status().isAccepted())
                .andReturn();
        String jobId = objectMapper.readValue(submission.getResponse().getContentAsString(), Map.class).get("jobId").toString();

        waitForStatus(jobId, "COMPLETED");

        mockMvc.perform(withApiKey(get("/job/{id}/download", jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].output").value("output:local"));
    }

    @Test
    void rejectsLocalPathOutsideWorkspace() throws Exception {
        mockMvc.perform(withApiKey(post("/job/local").param("file", "../outside.json")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registersWebhookForExistingJob() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "prompts.json", MediaType.APPLICATION_JSON_VALUE,
                "[\"webhook\"]".getBytes());
        MvcResult submission = mockMvc.perform(withApiKey(multipart("/job").file(file).param("route", "cheap")))
                .andExpect(status().isAccepted()).andReturn();
        String jobId = objectMapper.readValue(submission.getResponse().getContentAsString(), Map.class).get("jobId").toString();

        mockMvc.perform(withApiKey(post("/job/{id}/webhook", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"callbackUrl\":\"http://localhost/callback\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callbackUrl").value("http://localhost/callback"));
    }

    @Test
    void rejectsWebhookHostThatIsNotAllowlisted() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "prompts.json", MediaType.APPLICATION_JSON_VALUE,
                "[\"webhook\"]".getBytes());
        MvcResult submission = mockMvc.perform(withApiKey(multipart("/job").file(file).param("route", "cheap")))
                .andExpect(status().isAccepted()).andReturn();
        String jobId = objectMapper.readValue(submission.getResponse().getContentAsString(), Map.class).get("jobId").toString();

        mockMvc.perform(withApiKey(post("/job/{id}/webhook", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"callbackUrl\":\"http://evil.example/callback\"}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsRequestsWithoutApiKey() throws Exception {
        mockMvc.perform(get("/job/missing/status"))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder withApiKey(MockHttpServletRequestBuilder request) {
        return request.header(ApiKeyAuthenticationFilter.HEADER, API_KEY);
    }

    private void waitForStatus(String jobId, String status) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            MvcResult result = mockMvc.perform(withApiKey(get("/job/{id}/status", jobId))).andReturn();
            if (result.getResponse().getContentAsString().contains(status)) return;
            Thread.sleep(10);
        }
    }
}
