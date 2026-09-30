package com.github.guoyyit.moduleresolverjava.web;

import com.github.guoyyit.moduleresolverjava.model.ModuleResolution;
import com.github.guoyyit.moduleresolverjava.model.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.service.ResolutionService;
import com.github.guoyyit.moduleresolverjava.state.EventJournal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/resolution")
public class ResolutionController {

    private final ResolutionService resolutionService;

    public ResolutionController(ResolutionService resolutionService) {
        this.resolutionService = resolutionService;
    }

    @GetMapping
    public ResolutionSnapshot snapshot() {
        return resolutionService.current();
    }

    @GetMapping("/version")
    public Map<String, Long> version() {
        return Map.of("version", resolutionService.currentVersion());
    }

    @GetMapping("/modules/{name}")
    public ResponseEntity<ModuleResolution> module(@PathVariable String name) {
        ModuleResolution resolution = resolutionService.current().modules().get(name);
        return resolution == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(resolution);
    }

    @GetMapping("/diagnostics")
    public Object diagnostics() {
        return resolutionService.current().globalDiagnostics();
    }

    @GetMapping("/journal")
    public List<EventJournal.JournalEvent> journal() {
        return resolutionService.journal();
    }

    @PostMapping("/files")
    public ResolutionSnapshot upsertFile(@RequestBody UpsertFileRequest request) {
        return resolutionService.upsertFile(request.file(), request.content());
    }

    @DeleteMapping("/files/{name}")
    public ResolutionSnapshot deleteFile(@PathVariable String name) {
        return resolutionService.deleteFile(name);
    }

    @PostMapping("/rebuild")
    public ResolutionSnapshot rebuild(@RequestBody Map<String, String> files) {
        return resolutionService.rebuild(files);
    }
}
