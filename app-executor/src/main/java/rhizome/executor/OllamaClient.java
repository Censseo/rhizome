package rhizome.executor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.json.JSONObject;

/**
 * Minimal Ollama client — the éclair-tier runtime for the MVP (WHITEPAPER §10.3).
 * One blocking call to {@code POST /api/generate} with greedy decoding pinned:
 * {@code temperature = 0} and a fixed seed, so two executors on the same
 * integer-quantised weights on CPU produce byte-identical output and the registry
 * can settle a task by exact match. (The profond tier — vLLM on GPU — settles by
 * the judging round instead, since GPU stacks are not bit-reproducible.)
 */
final class OllamaClient {

    private final String baseUrl;
    private final long seed;
    private final int numCtx;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    OllamaClient(String baseUrl, long seed, int numCtx) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.seed = seed;
        this.numCtx = numCtx;
    }

    /** Runs the pinned model greedily and returns the generated text. */
    String generate(String model, String system, String prompt) {
        JSONObject body = new JSONObject()
            .put("model", model)
            .put("system", system)
            .put("prompt", prompt)
            .put("stream", false)
            .put("options", new JSONObject()
                .put("temperature", 0)
                .put("top_p", 1)
                .put("top_k", 1)
                .put("seed", seed)
                .put("num_ctx", numCtx));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/generate"))
            .timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        try {
            HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IllegalStateException("ollama HTTP " + res.statusCode() + ": " + res.body());
            }
            return new JSONObject(res.body()).getString("response").strip();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("ollama request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ollama request interrupted", e);
        }
    }
}
