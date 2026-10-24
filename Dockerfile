# 운영 이미지 (M6 커밋 6, ADR-030). GitHub Actions(.github/workflows/image.yml)가 빌드해 GHCR에 올리고, 서버는 받아서 쓴다.
# 테스트는 CI(ci.yml)가 돌리므로 여기서는 빌드만 한다.
# 기반 이미지는 Java 21 줄을 따라가는 태그다(보안 패치를 받으려고 패치 버전을 고정하지 않는다). Tailwind CLI가 glibc 바이너리라
# Alpine(musl) 대신 Ubuntu(noble) 기반을 쓴다.

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace
# 의존성 층을 먼저 만들어, 소스만 바뀌었을 때 다시 받지 않게 한다
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY config config
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test && cp build/libs/*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-noble
# 헬스체크용 curl만 더한다
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
USER app
# 서버·JVM 시간대는 서울 (PRD 3.2). DB 시각은 UTC로 저장한다(ADR-002)
ENV TZ=Asia/Seoul \
    SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
