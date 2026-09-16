package ai.interfaceai.cua.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Records are (de)serialized via Jackson's built-in canonical-constructor
 * support (databind 2.12+, compiled with -parameters) -- no extra module
 * needed. java.time types are avoided in the schema (ISO-8601 strings are
 * used instead) specifically so this project has zero dependencies beyond
 * jsoup + jackson-databind.
 */
public final class JsonUtil {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonUtil() {}
}
