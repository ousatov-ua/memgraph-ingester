---
layout: home

hero:
  name: Memgraph Ingester
  text: Structure-aware RAG for code and project knowledge
  tagline: Index local source files into Memgraph so AI agents can discover symbols, follow relationships, search semantic code chunks, and keep durable project memory.
  image:
    src: https://raw.githubusercontent.com/ousatov-ua/memgraph-ingester/main/image/memgraph-ingester-readme-banner-800x400.png
    alt: Memgraph Ingester banner
  actions:
    - theme: brand
      text: Get Started
      link: /guides/getting-started
    - theme: alt
      text: GitHub
      link: https://github.com/ousatov-ua/memgraph-ingester

features:
  - title: Graph-first code context
    details: Creates project-scoped File, Package, Class, Interface, Annotation, Method, and Field nodes with source locations and relationships.
  - title: RAG that verifies
    details: Uses derived CodeChunk and MemoryChunk search nodes, then links retrieval back to canonical graph records and source files.
  - title: Local by default
    details: Reads files locally and writes to your Memgraph instance over Bolt. Source code is not uploaded by the ingester.
  - title: Multi-language ingestion
    details: Supports Java, JavaScript, TypeScript, Python, and ctags-detected fallback languages for structural inventories.
  - title: Agent instructions
    details: Can install Codex, Claude, Gemini, GitHub Copilot, or raw Cypher guidance into your project instruction files.
  - title: Durable memory
    details: Optional project memory stores rules, decisions, findings, tasks, risks, questions, and code references beside the code graph.
---

## The Short Version

Memgraph Ingester turns a repository into a queryable code graph for AI-assisted development.
Instead of making an agent repeatedly scan files, you can give it indexed structure, semantic
retrieval, source ranges, call edges, type relationships, and project memories.

```bash
docker run -p 7687:7687 -p 7444:7444 --name memgraph memgraph/memgraph-mage:3.9.0

memgraph-ingester \
  --source . \
  --bolt bolt://localhost:7687 \
  --project my-project \
  --wipe-project-code \
  --init-instructions \
  --apply-schema
```

Then connect your agent through
[`memgraph-ingester-mcp`](https://github.com/ousatov-ua/memgraph-ingester-mcp) and ask questions
about the codebase through graph-aware tools.
