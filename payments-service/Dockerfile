FROM eclipse-temurin:21-jdk-alpine@sha256:cd87715a8d45cfaa42419207c64680234f62785c49055cccd20437b5c9018380 AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src src
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-alpine@sha256:1a29e1fe337eb28b5bec30f0ee8ed29f0ff80ab6f75dcf9313efe82911065a52 AS runtime

RUN apk add --no-cache curl \
    && addgroup --system --gid 10001 app \
    && adduser --system --uid 10001 --ingroup app app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/payments-service-*.jar /app/payments-service.jar

USER app
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/payments-service.jar"]
