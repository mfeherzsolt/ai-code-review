package hu.demo.reviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/** Minimális GitHub REST API kliens a PR review-hoz. */
final class GitHubClient {

    record ChangedFile(String filename, String patch) {}

    record InlineComment(String path, int line, String body) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final String token;
    private final String prUrl;

    GitHubClient(String token, String repository, String prNumber) {
        this.token = token;
        this.prUrl = "https://api.github.com/repos/" + repository + "/pulls/" + prNumber;
    }

    String headSha() throws Exception {
        return MAPPER.readTree(send("GET", prUrl, null)).path("head").path("sha").asText();
    }

    List<ChangedFile> changedFiles() throws Exception {
        List<ChangedFile> files = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            JsonNode batch = MAPPER.readTree(send("GET", prUrl + "/files?per_page=100&page=" + page, null));
            for (JsonNode f : batch) {
                if (f.hasNonNull("patch") && !"removed".equals(f.path("status").asText())) {
                    files.add(new ChangedFile(f.path("filename").asText(), f.path("patch").asText()));
                }
            }
            if (batch.size() < 100) {
                break;
            }
        }
        return files;
    }

    void postReview(String commitSha, String summary, List<InlineComment> comments) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("commit_id", commitSha);
        body.put("body", summary);
        body.put("event", "COMMENT");
        ArrayNode array = body.putArray("comments");
        for (InlineComment c : comments) {
            ObjectNode n = array.addObject();
            n.put("path", c.path());
            n.put("line", c.line());
            n.put("side", "RIGHT");
            n.put("body", c.body());
        }
        send("POST", prUrl + "/reviews", MAPPER.writeValueAsString(body));
    }

    private String send(String method, String url, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28");
        if (json == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("GitHub API hiba: " + method + " " + url + " -> "
                    + response.statusCode() + " " + response.body());
        }
        return response.body();
    }
}
