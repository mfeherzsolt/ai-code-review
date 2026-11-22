package hu.demo.reviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenAI-kompatibilis chat completions kliens. Alapból a GitHub Models végpontot használja,
 * amit a GitHub (Copilot) fiók / az Actions GITHUB_TOKEN-je elér, külön szolgáltatói fiók nélkül.
 */
final class LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final URI endpoint;
    private final String apiKey;
    private final String model;

    LlmClient(String endpoint, String apiKey, String model) {
        this.endpoint = URI.create(endpoint);
        this.apiKey = apiKey;
        this.model = model;
    }

    /** Elküldi a system és user promptot, visszaadja a modell szöveges válaszát. */
    String complete(String system, String user) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 3000);
        body.put("temperature", 0.2);
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("LLM API hiba: " + response.statusCode() + " " + response.body());
        }
        JsonNode content = MAPPER.readTree(response.body()).path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("Üres LLM válasz: " + response.body());
        }
        return content.asText();
    }
}
