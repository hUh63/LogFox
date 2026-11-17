# MCP Server

LogFox exposes its logs to AI clients through a built-in
[MCP](https://modelcontextprotocol.io) (Model Context Protocol) server:
the `read_logs` / `get_stored_logs` style tools that existed in the
`LogFox_1.1` build are merged back into this (2.1.x) code base.

The server is implemented without any extra dependency (plain `ServerSocket`
+ Gson), so it does not affect the size or the startup cost of the app.

## Enabling

Settings → Service → **MCP Server**:

| Preference | Description |
| --- | --- |
| Enable MCP server | Starts / stops the `McpServerService` foreground service |
| Server port | Port to listen on, `8765` by default (edited through a dialog) |

The server binds to `0.0.0.0` and starts automatically on app launch while
the preference is enabled. A foreground notification shows the current port
and allows stopping the server.

## Endpoints

| Path | Method | Description |
| --- | --- | --- |
| `/sse` | GET | MCP SSE transport, first event is `endpoint` with the message URL |
| `/messages?sessionId=<id>` | POST | MCP JSON-RPC messages (responses are pushed over the SSE stream) |
| `/mcp` | POST | Plain JSON-RPC entry, response returned in place |
| `/logs` | GET | SSE stream of new log lines |
| `/logs/clear` | POST | Clear log buffers |
| `/query` | GET | Current filter query |
| `/query/set` | POST | Set filter query (`{"query": "..."}` or raw text) |
| `/filters` | GET | Enabled filters with their query representation |
| `/tools` | GET | List of MCP tools |
| `/tools/{name}/call` | POST | Call a tool with a JSON arguments object |
| `/health` | GET | Health check |
| `/help` | GET | Endpoint and tool listing |

Protocol version: `2024-11-05`.

## Tools

| Tool | Description |
| --- | --- |
| `read_logs` | Read logcat. `mode=snapshot` (default) / `live` / `dump`, `count` 1..500 |
| `get_stored_logs` | Read stored crash (`files/crashes`) and recording (`files/recordings`) logs |
| `set_query` | Set the log filter query |
| `get_query` | Get the current log filter query |
| `clear_logs` | Clear all logcat buffers |
| `get_filters` | Get all enabled log filters |

## Client configuration

Example for an SSE capable MCP client (phone IP, default port):

```json
{
  "mcpServers": {
    "logfox": {
      "url": "http://<phone-ip>:8765/sse"
    }
  }
}
```

For clients that only support stdio, bridge it with `mcp-remote`:

```json
{
  "mcpServers": {
    "logfox": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://<phone-ip>:8765/sse"]
    }
  }
}
```
