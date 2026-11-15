FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY order-service ./order-service
COPY inventory-service ./inventory-service
RUN mvn -B -DskipTests package
ARG OTEL_AGENT_VERSION=2.16.0
RUN mvn -B dependency:copy -Dartifact=io.opentelemetry.javaagent:opentelemetry-javaagent:${OTEL_AGENT_VERSION}:jar -DoutputDirectory=/agent && mv /agent/*.jar /agent/otel.jar
FROM eclipse-temurin:21-jre
ARG SERVICE=order-service
WORKDIR /app
COPY --from=build /src/${SERVICE}/target/${SERVICE}-1.0.0.jar app.jar
COPY --from=build /agent/otel.jar otel.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-javaagent:/app/otel.jar","-jar","/app/app.jar"]
