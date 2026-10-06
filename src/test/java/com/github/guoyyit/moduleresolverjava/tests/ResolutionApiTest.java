package com.github.guoyyit.moduleresolverjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "resolver.state-dir=target/test-state/api")
@AutoConfigureMockMvc
class ResolutionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void upsertQueryAndDeleteThroughApi() throws Exception {
        String coreBody = "{\"file\":\"zcore.module\",\"content\":\"module com.zcore\\nexports pkg.zcore\\n\"}";
        mockMvc.perform(post("/api/resolution/files")
                        .contentType(MediaType.APPLICATION_JSON).content(coreBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declarations['com.zcore'].name").value("com.zcore"));

        String appBody = "{\"file\":\"zapp.module\","
                + "\"content\":\"module com.zapp\\nrequires com.zcore\\n\"}";
        mockMvc.perform(post("/api/resolution/files")
                        .contentType(MediaType.APPLICATION_JSON).content(appBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules['com.zapp'].directDependencies[0]")
                        .value("com.zcore"));

        mockMvc.perform(get("/api/resolution/modules/com.zapp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transitiveDependencies").isArray());

        mockMvc.perform(get("/api/resolution/modules/com.does.not.exist"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/resolution/files/zcore.module"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declarations['com.zcore']").doesNotExist());
    }

    @Test
    void malformedFileIsReportedWithPosition() throws Exception {
        String body = "{\"file\":\"zbad.module\",\"content\":\"nope\\n\"}";
        mockMvc.perform(post("/api/resolution/files")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.globalDiagnostics[?(@.position.file == 'zbad.module')]").exists())
                .andExpect(jsonPath(
                        "$.files['zbad.module'].diagnostics[0].code").value("UNKNOWN_DIRECTIVE"))
                .andExpect(jsonPath(
                        "$.files['zbad.module'].diagnostics[0].position.line").value(1));
    }

    @Test
    void transitiveVisibilityIsExposedThroughApi() throws Exception {
        mockMvc.perform(post("/api/resolution/files")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file\":\"tcore.module\",\"content\":\"module com.tcore\\nexports pkg.tcore\\n\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/resolution/files")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file\":\"tmid.module\",\"content\":\"module com.tmid\\nrequires transitive com.tcore\\n\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/resolution/files")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file\":\"tapp.module\",\"content\":\"module com.tapp\\nrequires com.tmid\\n\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules['com.tapp'].readableModules[0]")
                        .value("com.tcore"))
                .andExpect(jsonPath("$.modules['com.tapp'].readableModules[1]")
                        .value("com.tmid"))
                .andExpect(jsonPath("$.modules['com.tmid'].propagatedModules[0]")
                        .value("com.tcore"));

        // Removing the modifier hides the indirect package again.
        mockMvc.perform(post("/api/resolution/files")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file\":\"tmid.module\",\"content\":\"module com.tmid\\nrequires com.tcore\\n\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules['com.tapp'].readableModules[0]")
                        .value("com.tmid"))
                .andExpect(jsonPath("$.modules['com.tapp'].readableModules.length()")
                        .value(1));
    }
}
