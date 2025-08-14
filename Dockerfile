
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace

RUN apk add --no-cache bash coreutils findutils


COPY gradlew ./
COPY gradle gradle
RUN chmod +x gradlew


COPY build.gradle settings.gradle ./

RUN ./gradlew --no-daemon dependencies || true


COPY . .

RUN ./gradlew --no-daemon clean bootJar -x test \
 && cp build/libs/*-SNAPSHOT.jar /workspace/app.jar


FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /workspace/app.jar /app/app.jar


EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod \
    SPRING_DATA_REDIS_HOST=redis \
    SPRING_DATA_REDIS_PORT=6379 \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75"


ENTRYPOINT ["java","-jar","/app/app.jar"]