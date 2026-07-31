# 멀티스테이지: 빌드(JDK) → 런타임(JRE)
FROM eclipse-temurin:17-jdk AS build
WORKDIR /build
COPY . .
RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Seoul
# 부트 실행 가능 jar (plain jar 미생성: bootJar 단독 실행)
COPY --from=build /build/build/libs/*.jar app.jar
EXPOSE 8080
# JAVA_OPTS 로 힙 등 조정 (compose 에서 주입)
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
