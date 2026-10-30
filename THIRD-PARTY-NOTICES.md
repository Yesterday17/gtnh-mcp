# Third-party notices

GTNH MCP bundles the following libraries. Their classes and service descriptors are relocated under `io.github.gtnhmcp.shadow` to isolate them from the Minecraft classpath; their license and notice text is preserved without modification in the directories below. Upstream library source code has not been modified.

The Minecraft, Forge, NEI, CodeChickenCore and GTNHLib APIs used for compilation are supplied by the GTNH installation and are not bundled in this mod.

| Bundled artifact | License | Preserved license files |
| --- | --- | --- |
| [io.modelcontextprotocol.sdk:mcp:2.1.0](https://github.com/modelcontextprotocol/java-sdk/tree/v2.1.0) | MIT | `META-INF/licenses/mcp-2.1.0/` |
| [io.modelcontextprotocol.sdk:mcp-json-jackson3:2.1.0](https://github.com/modelcontextprotocol/java-sdk/tree/v2.1.0) | MIT | `META-INF/licenses/mcp-json-jackson3-2.1.0/` |
| [io.modelcontextprotocol.sdk:mcp-core:2.1.0](https://github.com/modelcontextprotocol/java-sdk/tree/v2.1.0) | MIT | `META-INF/licenses/mcp-core-2.1.0/` |
| [org.slf4j:slf4j-api:2.0.17](https://www.slf4j.org/license.html) | MIT | `META-INF/licenses/slf4j-api-2.0.17/` |
| [com.fasterxml.jackson.core:jackson-annotations:2.21](https://github.com/FasterXML/jackson-annotations) | Apache-2.0 | `META-INF/licenses/jackson-annotations-2.21/` |
| [io.projectreactor:reactor-core:3.7.0](https://github.com/reactor/reactor-core/tree/v3.7.0) | Apache-2.0 | `META-INF/licenses/reactor-core-3.7.0/` |
| [org.reactivestreams:reactive-streams:1.0.4](https://github.com/reactive-streams/reactive-streams-jvm/tree/v1.0.4) | MIT-0 | `META-INF/licenses/reactive-streams-1.0.4/` |
| [tools.jackson.core:jackson-databind:3.1.4](https://github.com/FasterXML/jackson-databind) | Apache-2.0 | `META-INF/licenses/jackson-databind-3.1.4/` |
| [tools.jackson.core:jackson-core:3.1.4](https://github.com/FasterXML/jackson-core) | Apache-2.0 | `META-INF/licenses/jackson-core-3.1.4/` |
| [com.networknt:json-schema-validator:3.0.6](https://github.com/networknt/json-schema-validator/tree/3.0.6) | Apache-2.0 | `META-INF/licenses/json-schema-validator-3.0.6/` |
| [com.ethlo.time:itu:1.14.0](https://github.com/ethlo/itu/tree/v1.14.0) | Apache-2.0 | `META-INF/licenses/itu-1.14.0/` |
| [tools.jackson.dataformat:jackson-dataformat-yaml:3.1.4](https://github.com/FasterXML/jackson-dataformats-text) | Apache-2.0 | `META-INF/licenses/jackson-dataformat-yaml-3.1.4/` |
| [org.snakeyaml:snakeyaml-engine:3.0.1](https://bitbucket.org/snakeyaml/snakeyaml-engine/src/snakeyaml-engine-3.0.1/) | Apache-2.0 | `META-INF/licenses/snakeyaml-engine-3.0.1/` |

Exact artifact checksums are recorded in `META-INF/shaded-dependencies.json`. License texts are sourced from the Maven artifact itself when present; otherwise from the matching upstream release tag.
