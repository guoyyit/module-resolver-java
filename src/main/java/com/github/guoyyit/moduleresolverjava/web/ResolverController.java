package com.github.guoyyit.moduleresolverjava.web;

import com.github.guoyyit.moduleresolverjava.resolve.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.system.IncrementalModuleSystem;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * Minimal HTTP surface demonstrating concurrent consistent queries and
 * updates over the resolver.
 */
@RestController
@RequestMapping("/api/resolver")
public class ResolverController {

    private final IncrementalModuleSystem system;

    public ResolverController(IncrementalModuleSystem system) {
        this.system = system;
    }

    @PostConstruct
    public void recover() {
        try {
            system.recover();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to recover resolver state", exception);
        }
    }

    @GetMapping("/snapshot")
    public ResolutionSnapshot snapshot() {
        return system.current();
    }

    public record UpsertRequest(String file, String content) {
    }

    @PostMapping("/files")
    public ResolutionSnapshot upsert(@RequestBody UpsertRequest request) {
        if (request == null || request.file() == null || request.content() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "fields 'file' and 'content' are required");
        }
        return system.upsertFile(request.file(), request.content());
    }
}
