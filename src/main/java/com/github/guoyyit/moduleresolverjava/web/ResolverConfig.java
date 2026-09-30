package com.github.guoyyit.moduleresolverjava.web;

import com.github.guoyyit.moduleresolverjava.system.IncrementalModuleSystem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Wires the resolver facade with a local on-disk state directory so the
 * whole flow is reproducible without external services.
 */
@Configuration
public class ResolverConfig {

    @Bean
    public IncrementalModuleSystem incrementalModuleSystem(
            @Value("${moduleresolver.state-dir:./target/resolver-state}") String stateDir) {
        return new IncrementalModuleSystem(Path.of(stateDir));
    }
}
