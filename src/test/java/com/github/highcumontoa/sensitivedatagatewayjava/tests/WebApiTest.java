package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WebApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private String requestJson(String caller, String purpose, String version,
                                Object user) throws Exception {
        var root = new java.util.HashMap<String, Object>();
        root.put("callerId", caller);
        root.put("purpose", purpose);
        if (version != null) {
            root.put("policyVersion", version);
        }
        var payload = new java.util.HashMap<String, Object>();
        payload.put("user", user);
        root.put("payload", payload);
        return objectMapper.writeValueAsString(root);
    }

    private java.util.Map<String, Object> user() {
        var user = new java.util.HashMap<String, Object>();
        user.put("name", "Bob");
        user.put("email", "bob@example.com");
        user.put("phone", "13900000000");
        return user;
    }

    @Test
    void allowResponseContainsMarkers() throws Exception {
        mockMvc.perform(post("/api/v1/gateway/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("svc-analytics", "analytics", null, user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditId").exists())
                .andExpect(jsonPath("$.policyVersion").exists())
                .andExpect(jsonPath("$.data.user.name").value("Bob"))
                .andExpect(jsonPath("$.data.user.phone").value("1*********0"))
                .andExpect(jsonPath("$.data.user.email", org.hamcrest.Matchers.startsWith("tok_")))
                .andExpect(jsonPath("$.fieldMarkers[?(@.irreversible==true)]").exists());
    }

    @Test
    void unauthorizedReturns403DistinctCode() throws Exception {
        mockMvc.perform(post("/api/v1/gateway/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("nobody", "analytics", null, user())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED_CALLER"))
                .andExpect(jsonPath("$.audited").value(true));
    }

    @Test
    void purposeMismatchReturnsDistinctCode() throws Exception {
        mockMvc.perform(post("/api/v1/gateway/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("svc-support", "analytics", null, user())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURPOSE_MISMATCH"));
    }

    @Test
    void fallbackReturns409() throws Exception {
        // 取最旧版本
        String resp = mockMvc.perform(get("/api/v1/admin/policies"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(resp);
        String oldest = node.get("versions").get(0).asText();

        mockMvc.perform(post("/api/v1/gateway/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("svc-analytics", "analytics", oldest, user())))
                .andExpect(status().is(409))
                .andExpect(jsonPath("$.code").value("POLICY_FALLBACK_DENIED"));
    }

    @Test
    void malformedJsonNormalizedToBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/gateway/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void auditEndpointRecordsRawInputAndBasis() throws Exception {
        String secret = "carol@example.com";
        var u = user();
        u.put("email", secret);
        mockMvc.perform(post("/api/v1/gateway/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("svc-analytics", "analytics", null, u)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/audits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.inputJson =~ /.*carol@example.com.*/)]").exists())
                .andExpect(jsonPath("$[?(@.decisionCode == 'ALLOW')]").exists());
    }
}
