package io.github.neherim.ruslo.perf.report;

import io.github.neherim.ruslo.core.utils.JsonUtils;
import io.github.neherim.ruslo.perf.config.BenchmarkEnvironment;
import org.slf4j.Logger;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Saves raw JSON and rebuilds the single Markdown report of the current Maven run. */
public final class BenchmarkReportWriter {
    private BenchmarkReportWriter() {}

    /** Saves the result of a verified scenario and rebuilds performance-report.md. */
    public static Path write(String benchmark, BenchmarkEnvironment environment, Map<String, Object> workload,
                             Object iterations, Logger log) {
        var directory = Path.of(System.getProperty("ruslo.perf.output-directory"));
        var output = directory.resolve(benchmark + ".json");
        var report = new LinkedHashMap<String, Object>();
        report.put("benchmark", benchmark);
        report.put("timestampUtc", Instant.now().toString());
        report.put("environment", metadata(environment));
        report.put("workload", workload);
        report.put("iterations", iterations);
        try {
            Files.createDirectories(directory);
            if (Files.exists(output)) throw new IllegalStateException("Benchmark output already exists: " + output);
            JsonUtils.DEFAULT_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            var reports = new ArrayList<JsonNode>();
            for (var name : List.of("process-ingestion", "process-execution")) {
                var source = directory.resolve(name + ".json");
                if (Files.exists(source)) reports.add(JsonUtils.DEFAULT_OBJECT_MAPPER.readTree(source.toFile()));
            }
            Files.writeString(directory.resolve("performance-report.md"), BenchmarkMarkdownReport.render(reports));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write benchmark report " + output, ex);
        }
        log.info("Benchmark {} completed: report={}", benchmark, directory.resolve("performance-report.md"));
        return output;
    }

    private static Map<String, Object> metadata(BenchmarkEnvironment environment) {
        var result = new LinkedHashMap<String, Object>();
        result.put("gitCommit", git("rev-parse", "HEAD"));
        var changes = git("status", "--porcelain");
        result.put("gitWorkingTree", changes.equals("unknown") ? "unknown" : changes.isEmpty() ? "clean" : "dirty");
        result.put("gitChanges", changes);
        result.put("hardwareAndDockerNotes", System.getProperty("ruslo.perf.environment", "not provided"));
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("jvm", System.getProperty("java.vm.name"));
        result.put("jvmArguments", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        result.put("os", System.getProperty("os.name"));
        result.put("arch", System.getProperty("os.arch"));
        result.put("jvmCpuCount", Runtime.getRuntime().availableProcessors());
        result.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        result.put("postgres", environment.postgresMetadata());
        return result;
    }

    private static String git(String... arguments) {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return "unknown";
            return new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            return "unknown";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "unknown";
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
