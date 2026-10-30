package io.github.gtnhmcp.protocol;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Standalone packaging smoke test: run with test classes and ONLY the shaded mod jar on the classpath. Uses a fake
 * tool,
 * not a game world. Successful startup verifies relocated SDK ServiceLoader/JSON wiring independently of Gradle's deps.
 */
public final class ShadedEndpointProbe {

    private ShadedEndpointProbe() {}

    public static void main(String[] args) throws Exception {
        String token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        ToolDefinition tool = new ToolDefinition(
            "packaging_probe",
            "Fake tool for artifact packaging verification.",
            Collections.singletonMap("type", "object"));
        try (McpEndpoint endpoint = new McpEndpoint(
            0,
            token,
            Collections.singletonList(tool),
            (name, arguments) -> Collections.singletonMap("fakeProbe", true))) {
            endpoint.start();
            String initialize = post(
                endpoint.getEndpointUrl(),
                token,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"shaded-probe\",\"version\":\"1\"}}}");
            String listing = post(
                endpoint.getEndpointUrl(),
                token,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            String result = post(
                endpoint.getEndpointUrl(),
                token,
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"packaging_probe\",\"arguments\":{}}}");
            if (!initialize.contains("2025-11-25") || !listing.contains("packaging_probe")
                || !result.contains("\"fakeProbe\":true")
                || !result.contains("\"isError\":false")) {
                throw new AssertionError("Shaded endpoint returned an unexpected protocol response");
            }
            System.out.println(
                "PASS: shaded artifact initialize, tools/list and tools/call over real loopback HTTP (fake game callback)");
        }
    }

    private static String post(String url, String token, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(6000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        connection.setRequestProperty("MCP-Protocol-Version", "2025-11-25");
        connection.setDoOutput(true);
        byte[] request = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(request.length);
        try (java.io.OutputStream output = connection.getOutputStream()) {
            output.write(request);
        }
        try {
            if (connection.getResponseCode() != 200) {
                throw new AssertionError("Unexpected HTTP status: " + connection.getResponseCode());
            }
            try (InputStream input = connection.getInputStream();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    bytes.write(buffer, 0, count);
                }
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
