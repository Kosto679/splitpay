FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/target/splitpay.jar app.jar

ENV DATA_DIR=/data
# Sized for a handful of requests a day: small heap, one GC thread, C1-only JIT.
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -Xms16m -Xmx48m -Xss256k \
    -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=24m \
    -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -XX:MaxDirectMemorySize=16m"
EXPOSE 9000

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
