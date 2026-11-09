package com.bank.e2e;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for Tempo: accepts what the services export over OTLP and keeps it. The payloads are
 * protobuf, but a trace id is stored as its 16 raw bytes and a service name as plain text, so both can
 * be found without decoding. Each export comes from one service only.
 */
final class TraceReceiver implements AutoCloseable {

    private final HttpServer server;
    private final List<byte[]> exports = new CopyOnWriteArrayList<>();

    TraceReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            exports.add(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces";
    }

    /** Whether the named service has exported a span belonging to this trace. */
    boolean hasSpan(String service, String traceIdHex) {
        byte[] traceId = HexFormat.of().parseHex(traceIdHex);
        byte[] name = service.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return exports.stream().anyMatch(export -> contains(export, name) && contains(export, traceId));
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
