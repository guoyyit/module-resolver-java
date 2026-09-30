package com.github.guoyyit.moduleresolverjava;

import com.github.guoyyit.moduleresolverjava.model.Diagnostic;
import com.github.guoyyit.moduleresolverjava.resolve.ResolutionSnapshot;
import com.github.guoyyit.moduleresolverjava.system.IncrementalModuleSystem;

import java.nio.file.Path;
import java.util.List;

/**
 * Local, reproducible command-line entry point.
 *
 * <p>Usage:
 * <pre>
 * mvn -q exec:java -Dexec.mainClass=...ResolverCli \
 *     -Dexec.args="&lt;modules-dir&gt; &lt;state-dir&gt;"
 * </pre>
 * Or after packaging:
 * {@code java -jar target/*.jar <modules-dir> <state-dir>}.
 * Exits with code 1 when the resolved snapshot contains errors.</p>
 */
public final class ResolverCli {

    private ResolverCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ResolverCli <modules-dir> <state-dir>");
            System.exit(2);
        }
        IncrementalModuleSystem system =
                new IncrementalModuleSystem(Path.of(args[1]));
        system.recover();
        ResolutionSnapshot snapshot = system.syncDirectory(Path.of(args[0]), null);

        System.out.println("snapshot version : " + snapshot.version());
        System.out.println("modules          : " + snapshot.modules().size());
        System.out.println("package owners   : " + snapshot.packageOwners().size());
        System.out.println("service bindings : " + snapshot.serviceBindings().size());
        System.out.println("parse failures   : " + snapshot.parseFailures().size());
        List<Diagnostic> diagnostics = snapshot.diagnostics();
        if (diagnostics.isEmpty()) {
            System.out.println("diagnostics      : none");
        } else {
            System.out.println("diagnostics      : " + diagnostics.size());
            for (Diagnostic diagnostic : diagnostics) {
                String location = diagnostic.file() == null ? "-"
                        : diagnostic.file()
                                + (diagnostic.line() == null ? ""
                                : ":" + diagnostic.line());
                System.out.println("  [" + diagnostic.severity() + "] "
                        + diagnostic.code() + " @ " + location + " - "
                        + diagnostic.message());
            }
        }
        System.exit(snapshot.hasErrors() ? 1 : 0);
    }
}
