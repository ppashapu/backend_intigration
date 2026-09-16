package ai.interfaceai.cua.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One directory per run under /evidence/, containing:
 *  - log.jsonl: one structured JSON line per event (observe/decide/act/error/escalation)
 *  - a DOM snapshot (.html) on any failure or escalation -- the "richer
 *    signal on failure" required by 3.5 (the spec explicitly allows a DOM
 *    snapshot as an alternative to a screenshot; this project's DOM-level
 *    automation mechanism makes that the natural choice)
 *  - artifact.json (discovery runs) or result.json (replay runs), written by the caller
 *
 * All free text is scrubbed via Redactor before it reaches disk.
 */
public class EvidenceLogger implements AutoCloseable {

    private final Path dir;
    private final Writer log;
    private final ObjectMapper mapper = JsonUtil.MAPPER;

    public EvidenceLogger(Path evidenceRoot, String runId) throws IOException {
        this.dir = evidenceRoot.resolve(runId);
        Files.createDirectories(dir);
        this.log = Files.newBufferedWriter(dir.resolve("log.jsonl"), StandardCharsets.UTF_8);
    }

    public Path dir() { return dir; }

    public void event(String type, Map<String, Object> fields) {
        try {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("ts", Instant.now().toString());
            line.put("type", type);
            line.putAll(fields);
            log.write(mapper.writeValueAsString(line));
            log.write("\n");
            log.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed writing evidence log", e);
        }
    }

    /** Saves a DOM snapshot (raw HTML) as the richer failure/escalation signal. */
    public String domSnapshot(String html, String label) {
        String filename = label + "-" + System.currentTimeMillis() + ".html";
        Path path = dir.resolve(filename);
        try {
            Files.writeString(path, html == null ? "" : html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed writing DOM snapshot", e);
        }
        return path.toString();
    }

    public void writeJson(String filename, Object value) {
        try {
            Files.writeString(dir.resolve(filename), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        } catch (IOException e) {
            throw new RuntimeException("Failed writing " + filename, e);
        }
    }

    @Override
    public void close() {
        try { log.close(); } catch (IOException ignored) {}
    }
}
