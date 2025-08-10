# ===== 1) Build stage =====
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace

# gradle 캐시 최적화
COPY gradlew ./
COPY gradle gradle
RUN chmod +x gradlew

# 의존성 먼저 캐시
COPY build.gradle settings.gradle ./
# (멀티 모듈이면 필요한 build.gradle.kts/세부 설정 추가 복사)
RUN ./gradlew dependencies || true

# 앱 소스 빌드
COPY . .
RUN ./gradlew clean build -x test

# ===== 2) Runtime stage =====
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 빌드 산출물 JAR 복사 (경로는 프로젝트에 맞게 조정)
COPY --from=build /workspace/build/libs/*SNAPSHOT*.jar /app/app.jar

# Actuator/헬스체크, 기본 포트
EXPOSE 8080

# 컨테이너 실행 시 환경변수로 Redis/프로필 주입 가능
ENV SPRING_PROFILES_ACTIVE=prod \
    SPRING_DATA_REDIS_HOST=localhost \
    SPRING_DATA_REDIS_PORT=6379

ENTRYPOINT ["java","-jar","/app/app.jar"]