java -jar memgraph-ingester/target/memgraph-ingester-1.0.0.jar \
     --source <project>/src/main/java \
     --bolt   bolt+ssc://<host>:7687 \
     --user   <user> --pass "<pass>" \
     --wipe
