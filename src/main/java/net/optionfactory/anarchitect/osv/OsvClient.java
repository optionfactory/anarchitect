package net.optionfactory.anarchitect.osv;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.optionfactory.anarchitect.osv.OsvClient.OsvBatchResponse.OsvResult;
import net.optionfactory.anarchitect.osv.OsvClient.OsvBatchResponse.OsvResult.OsvVulnerability;
import tools.jackson.databind.json.JsonMapper;

public class OsvClient {

    private final JsonMapper mapper;
    private final HttpClient httpClient;

    public OsvClient(JsonMapper mapper) {
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public record ArtifactInfo(String groupId, String artifactId, String version) {

        public String coords() {
            return groupId + ":" + artifactId;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OsvBatchResponse(List<OsvResult> results) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record OsvResult(List<OsvVulnerability> vulns) {

            @JsonIgnoreProperties(ignoreUnknown = true)
            public record OsvVulnerability(String id) {

            }

        }

    }

    public Map<ArtifactInfo, List<String>> queryBatch(List<ArtifactInfo> artifacts) throws IOException, InterruptedException {
        final var result = new HashMap<ArtifactInfo, List<String>>();
        if (artifacts.isEmpty()) {
            return result;
        }

        final var queries = artifacts.stream()
                .map(a -> Map.of("package", Map.of("name", a.coords(), "ecosystem", "Maven"), "version", a.version()))
                .toList();

        final var request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.osv.dev/v1/querybatch"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("queries", queries))))
                .timeout(Duration.ofSeconds(15))
                .build();

        final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("failed to query osv");
        }

        final var batchResponse = mapper.readValue(response.body(), OsvBatchResponse.class);
        final var results = batchResponse.results() != null ? batchResponse.results() : List.<OsvResult>of();

        for (int i = 0; i < results.size() && i < artifacts.size(); i++) {
            final var vulns = results.get(i).vulns();
            if (vulns != null && !vulns.isEmpty()) {
                result.put(artifacts.get(i), vulns.stream()
                        .map(OsvVulnerability::id)
                        .toList());
            }
        }
        return result;
    }
}
