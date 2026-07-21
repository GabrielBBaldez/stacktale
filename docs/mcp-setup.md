# Using the stacktale MCP server

`stacktale-mcp` lets an AI assistant read your app's error reports as tools â€” `list_errors`,
`get_report`, `errors_since`, `find_similar_errors` â€” and, with a subscription, be **notified
the moment a new error lands** instead of polling. It's a tiny read-only server that speaks
[MCP](https://modelcontextprotocol.io) over stdio. No network, no writes.

## Fastest path (2 minutes)

If you have [JBang](https://www.jbang.dev), there's nothing to download. From your
**project root** (so `$PWD` resolves the log path for you):

**Claude Code** â€” one command:

```bash
claude mcp add stacktale -- jbang run io.github.gabrielbbaldez:stacktale-mcp:0.5.0 --file "$PWD/errors-ai.log"
```

**Cursor** â€” drop this into `.cursor/mcp.json` (swap in your absolute path):

```json
{ "mcpServers": { "stacktale": {
    "command": "jbang",
    "args": ["run", "io.github.gabrielbbaldez:stacktale-mcp:0.5.0", "--file", "/abs/path/errors-ai.log"]
} } }
```

Then ask your assistant *"what errors has the app had recently?"*. That's it â€” the log
file doesn't even need to exist yet; the server picks it up when the first error lands.

The rest of this page covers downloading the jar directly (no JBang), the env-var option,
Claude Desktop, and troubleshooting.

## Get the jar

It's on Maven Central. Three ways, easiest first:

**JBang** (zero install, if you have JBang):

```bash
jbang run io.github.gabrielbbaldez:stacktale-mcp:0.5.0 --file /path/to/errors-ai.log
```

**Download the jar directly** (curl):

```bash
curl -L -o stacktale-mcp.jar \
  https://repo1.maven.org/maven2/io/github/gabrielbbaldez/stacktale-mcp/0.5.0/stacktale-mcp-0.5.0.jar
```

**Maven** (into a folder you choose):

```bash
mvn dependency:copy \
  -Dartifact=io.github.gabrielbbaldez:stacktale-mcp:0.5.0 \
  -DoutputDirectory=.
```

## Configure the file

The server reads one report file. Point it there with either:

- the `--file /path/to/errors-ai.log` argument, or
- the `STACKTALE_FILE` environment variable (cleaner in some client configs).

If neither is set it defaults to `errors-ai.log` in the working directory.

## Client setup

### Claude Code

Add it with one command from your project directory:

```bash
claude mcp add stacktale -- java -jar /abs/path/stacktale-mcp.jar --file /abs/path/errors-ai.log
```

Or edit `.mcp.json` in the project root:

```json
{
  "mcpServers": {
    "stacktale": {
      "command": "java",
      "args": ["-jar", "/abs/path/stacktale-mcp.jar", "--file", "/abs/path/errors-ai.log"]
    }
  }
}
```

### Claude Desktop

Edit `claude_desktop_config.json`
(macOS: `~/Library/Application Support/Claude/`, Windows: `%APPDATA%\Claude\`):

```json
{
  "mcpServers": {
    "stacktale": {
      "command": "java",
      "args": ["-jar", "C:/abs/path/stacktale-mcp.jar"],
      "env": { "STACKTALE_FILE": "C:/abs/path/errors-ai.log" }
    }
  }
}
```

Restart Claude Desktop after editing.

### Cursor

`.cursor/mcp.json` in the project (or the global equivalent):

```json
{
  "mcpServers": {
    "stacktale": {
      "command": "java",
      "args": ["-jar", "/abs/path/stacktale-mcp.jar", "--file", "/abs/path/errors-ai.log"]
    }
  }
}
```

## Try it

Once wired up, ask your assistant:

> *What errors has the app had recently?* â€” it calls `list_errors`.
> *Show me the full report for #c73cf755* â€” it calls `get_report`.
> *What broke since 11am?* â€” it calls `errors_since`.
> *Have we seen this NPE before?* â€” it calls `find_similar_errors`.

## Troubleshooting

- **"command not found: java"** â€” the server runs on the JVM; Java must be on the `PATH`
  the client launches with. On Windows, use forward slashes in paths or double backslashes.
- **Nothing shows up** â€” always use **absolute** paths for both the jar and the log file;
  MCP clients don't launch from your project directory.
- **No live notifications** â€” subscriptions require a client that supports MCP resource
  subscriptions; tools (`list_errors` etc.) work everywhere.
