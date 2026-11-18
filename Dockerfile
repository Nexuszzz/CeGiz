FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY lib/json-20240303.jar lib/json-20240303.jar
RUN echo "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed  lib/json-20240303.jar" | sha256sum -c -
COPY src/main/java src/main/java
COPY src/test/java src/test/java
RUN find src/main/java -name '*.java' > main-sources.txt \
    && mkdir -p classes test-classes \
    && javac --release 17 -encoding UTF-8 -cp lib/json-20240303.jar -d classes @main-sources.txt \
    && find src/test/java -name '*.java' > test-sources.txt \
    && javac --release 17 -encoding UTF-8 -cp classes:lib/json-20240303.jar -d test-classes @test-sources.txt \
    && java -cp classes:test-classes:lib/json-20240303.jar com.cegiz.server.ServerTest

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/classes /app/classes
COPY --from=build /build/lib/json-20240303.jar /app/lib/json-20240303.jar
USER 10001:10001
EXPOSE 8080
CMD ["java", "-Xmx192m", "-cp", "/app/classes:/app/lib/json-20240303.jar", "com.cegiz.server.CeGizServer"]
