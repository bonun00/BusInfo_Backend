
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

# 학습 실행(CDS)과 실제 실행이 같은 프로필·JVM 옵션을 쓰도록 먼저 선언
ENV SPRING_PROFILES_ACTIVE=prod \
    SPRING_DATA_REDIS_HOST=redis \
    SPRING_DATA_REDIS_PORT=6379 \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

# fat jar → 일반 jar 구조로 풀기 (CDS는 nested jar를 지원하지 않음)
COPY --from=build /workspace/app.jar /tmp/app.jar
RUN java -Djarmode=tools -jar /tmp/app.jar extract --destination /app/application \
 && rm /tmp/app.jar

# AppCDS 학습 실행: 컨텍스트 refresh 직후 종료하며 로딩된 클래스를 아카이브로 저장
# (Tomcat·Redis 연결 전에 종료되므로 빌드 시 Redis 불필요. service-key는 더미 값 — 실제 키 금지)
RUN java -XX:ArchiveClassesAtExit=/app/application.jsa \
         -Dspring.context.exit=onRefresh \
         -Dpublic-api.service-key=cds-training-dummy \
         -jar /app/application/app.jar \
 && test -f /app/application.jsa

USER app

EXPOSE 8080

ENTRYPOINT ["java","-XX:SharedArchiveFile=/app/application.jsa","-jar","/app/application/app.jar"]
