claude mcp add memgraph --scope user \
  --env MEMGRAPH_URL=bolt+ssc://<host>:7687 \
  --env MEMGRAPH_USER=<user> \
  --env MEMGRAPH_PASSWORD=<pass> \
  -- uvx mcp-memgraph