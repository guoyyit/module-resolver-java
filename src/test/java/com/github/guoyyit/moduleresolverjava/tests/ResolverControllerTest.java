package com.github.guoyyit.moduleresolverjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "moduleresolver.state-dir=target/resolver-state-web-test")
@AutoConfigureMockMvc
class ResolverControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void queriesSnapshotAndUpsertsModule() throws Exception {
        mockMvc.perform(get("/api/resolver/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").exists());

        String content = """
                module com.web.demo {
                    exports pkg.web;
                }
                """;
        mockMvc.perform(post("/api/resolver/files")
                        .contentType("application/json")
                        .content("{\"file\":\"/web/demo.mod\",\"content\":"
                                + jacksonString(content) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules['com.web.demo'].name")
                        .value("com.web.demo"))
                .andExpect(jsonPath("$.packageOwners['pkg.web']")
                        .value("com.web.demo"));

        mockMvc.perform(post("/api/resolver/files")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private static String jacksonString(String raw) {
        StringBuilder builder = new StringBuilder("\"");
        for (char character : raw.toCharArray()) {
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(character);
            }
        }
        return builder.append('"').toString();
    }
}
