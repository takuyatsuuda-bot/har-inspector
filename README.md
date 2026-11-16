# HAR Inspector

A JetBrains IDE plugin that opens `.har` (HTTP Archive) files exported from browser DevTools right inside the IDE.

- Request table with method, status, type, size and time
- Waterfall column showing when each request started and how long it took
- Filter by URL, method, status or content type
- Headers, query, request body, response body and timing breakdown for the selected request
- Copy any request as cURL

Everything runs locally. No data leaves your machine.

## Build

```
./gradlew buildPlugin   # -> build/distributions/har-inspector-<version>.zip
./gradlew runIde        # opens samples/demo/example.har in a sandbox IDE
```

Requires IntelliJ-based IDEs 2025.1 or later.

## License

MIT
