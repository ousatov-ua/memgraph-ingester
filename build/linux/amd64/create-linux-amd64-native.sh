docker build \
  --platform linux/amd64 \
  -f Dockerfile.linux-native-builder \
  -t graalvm25-maven-native-amd64 .

docker run --rm -it \
  --platform linux/amd64 \
  --entrypoint /bin/bash \
  -v "$PWD":/workspace \
  -v "$HOME/.m2":/root/.m2 \
  -w /workspace \
  graalvm25-maven-native-amd64 \
 -lc 'mvn -Pnative-linux -DskipTests package'
