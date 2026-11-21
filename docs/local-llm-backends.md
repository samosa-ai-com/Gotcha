# Running an OpenAI-compatible local backend with Gotcha

Configure Gotcha with the server’s Base URL, including `/v1/`, and the model ID exposed by the server. Once **Test connection** succeeds, try a normal chat and a complete tool round trip. Use a synthetic tool result in a test harness so the round trip can be repeated without accessing real device data. [Gotcha setup guide](RUNNING.md#5-configure-the-llm-required-before-chatting)

## Keep the reusable prompt prefix stable

Inspect the prompt after the backend applies the model’s chat template. A prefix cache can reuse the tokens shared from the beginning of two prompts. An early change, such as a different working directory or timestamp, can force the backend to process the unchanged tool catalog that follows it again.

Where the template and message semantics allow, put stable instructions and tool definitions before volatile facts. Keep current facts, instruction priority and message roles intact. On the server, bound the cache’s memory use and measure whether the prefix still survives an interleaved request, such as chat title generation.

## Budget the prompt the model actually sees

Count tokens with the backend’s tokenizer after rendering the request. Include system messages, history, tool definitions, tool results and template overhead. Account for image inputs if the backend supports them. Leave room for generation, including reasoning and structured tool calls, within the context available to that request.

Treat cached tokens as part of the context budget. If history must be shortened, preserve essential instructions, the latest user request and any complete tool exchanges needed to continue. Keep each assistant tool call with its matching result. If the request still exceeds the available context, return a clear error.

## Keep the incoming tool catalog authoritative

If the backend experiments with a shortlist, select candidates only from the current request’s `tools[]`. A compact description can help the model select a candidate. Before returning a call, check the name and arguments against the full incoming schema and honor the request’s tool-choice settings. A schema-valid call can still be the wrong action for the user’s request.

Return standard `tool_calls` and accept the follow-up `role: "tool"` message with the matching `tool_call_id`. Keep selection-only messages inside the backend. Gotcha owns Android tool execution, permissions and confirmations.

If the shortlist yields no match or an invalid decision, use the original full request through the normal backend path when it fits. If the intent is ambiguous and the request allows a text response, a short clarification can be the useful result. Measure the cost of fallback as part of the request.

## Measure time to the first useful result

Use a fixed set of commands and compare each change separately against the baseline on the same device and configuration. Record model load separately from cold and warm prompt processing. Include an interleaved request in the warm-cache test. Report prompt processing, generation, peak memory and the time until the user gets a useful answer or tool result. For streaming, report time to the first visible token separately.

For tool selection, report **correct selections**, **wrong-tool accepts** and **full-request fallbacks** separately, with counts and denominators. Include commands that need no tool, ambiguous commands and commands whose tool is absent from the catalog. A wrong-tool accept counts even if Gotcha blocks execution.

In one earlier experiment with a custom Gotcha build and a 33-tool catalog, native server time was 65.4 seconds for a cold request and 1.5 seconds for a warm request. The two measurements used the same combined configuration and illustrate how different cold and warm behavior can be. [Experiment and timings](https://github.com/samosa-ai-com/Gotcha/issues/121#issuecomment-6049075852)
