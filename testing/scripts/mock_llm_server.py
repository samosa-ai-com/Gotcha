#!/usr/bin/env python3
"""Dependency-free OpenAI-compatible mock server for manual/Maestro test runs.

Serves canned responses on the same routes app/src/test/java/com/gotcha/llm/LLMClientTest.kt
exercises, so androidTest (MockWebServer, in-process) and Maestro (this server,
reached via `adb reverse tcp:8080 tcp:8080`) assert against the same reply text.

Usage:
    python3 testing/scripts/mock_llm_server.py [port]
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

MOCK_REPLY_OK = "MOCK_REPLY_OK"
DEFAULT_PORT = 8080


class MockLlmHandler(BaseHTTPRequestHandler):
    def _send_json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path.rstrip("/").endswith("/chat/completions"):
            length = int(self.headers.get("Content-Length", 0))
            self.rfile.read(length)  # drain the request body; content is unused
            self._send_json(
                200,
                {
                    "choices": [
                        {
                            "message": {"role": "assistant", "content": MOCK_REPLY_OK},
                            "finish_reason": "stop",
                        }
                    ]
                },
            )
        else:
            self._send_json(404, {"error": "not found"})

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            self._send_json(
                200,
                {"data": [{"id": "test-model", "object": "model"}]},
            )
        else:
            self._send_json(404, {"error": "not found"})

    def log_message(self, format, *args):  # noqa: A002 - matches BaseHTTPRequestHandler signature
        sys.stderr.write("[mock_llm_server] %s\n" % (format % args))


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT
    server = HTTPServer(("0.0.0.0", port), MockLlmHandler)
    print(f"[mock_llm_server] listening on :{port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
