package com.github.guoyyit.moduleresolverjava.config;

import com.github.guoyyit.moduleresolverjava.analysis.PackageConflictResolver;
import com.github.guoyyit.moduleresolverjava.analysis.ServiceBinder;
import com.github.guoyyit.moduleresolverjava.engine.ResolutionEngine;
import com.github.guoyyit.moduleresolverjava.graph.CycleDetector;
import com.github.guoyyit.moduleresolverjava.parser.ManifestParser;
import com.github.guoyyit.moduleresolverjava.service.ResolutionService;
import com.github.guoyyit.moduleresolverjava.state.EventJournal;
import com.github.guoyyit.moduleresolverjava.state.SnapshotStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class ResolutionConfig {

    @Bean
    public ManifestParser manifestParser() {
        return new ManifestParser();
    }

    @Bean
    public CycleDetector cycleDetector() {
        return new CycleDetector();
    }

    @Bean
    public PackageConflictResolver packageConflictResolver() {
        return new PackageConflictResolver();
    }

    @Bean
    public ServiceBinder serviceBinder() {
        return new ServiceBinder();
    }

    @Bean
    public ResolutionEngine resolutionEngine() {
        return new ResolutionEngine();
    }

    @Bean
    public SnapshotStore snapshotStore(@Value("${resolver.state-dir}") String stateDir) {
        return new SnapshotStore(Path.of(stateDir));
    }

    @Bean
    public EventJournal eventJournal(@Value("${resolver.state-dir}") String stateDir) {
        return new EventJournal(Path.of(stateDir));
    }

    @Bean
    public ResolutionService resolutionService(ManifestParser manifestParser,
                                               ResolutionEngine resolutionEngine,
                                               SnapshotStore snapshotStore,
                                               EventJournal eventJournal) {
        return new ResolutionService(manifestParser, resolutionEngine,
                snapshotStore, eventJournal);
    }
}
