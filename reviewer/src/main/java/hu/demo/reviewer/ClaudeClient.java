package hu.demo.reviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimális Anthropic Messages API kliens. */
final class ClaudeClient {

    private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final String apiKey;
    private final String model;

    ClaudeClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    /** Elküldi a system és user promptot, visszaadja a modell szöveges válaszát. */
    String complete(String system, String user) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 4096);
        body.put("system", system);
        body.putArray("messages").addObject().put("role", "user").put("content", user);

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofMinutes(2))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Anthropic API hiba: " + response.statusCode() + " " + response.body());
        }
        JsonNode content = MAPPER.readTree(response.body()).path("content");
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        return text.toString();
    }
}
