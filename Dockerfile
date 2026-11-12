# mspace-climate 실행 이미지. 두 단계로 나눠서 최종 이미지에는 JRE와 jar만 남긴다.

# ---------- 1단계: 빌드 (JDK + Maven Wrapper) ----------
# 빌드 결과(jar·정적 파일)는 CPU와 상관없어서, 빌드는 러너 CPU에서 한 번만 한다 (arm64 이미지도 빠르게)
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 의존성 목록(pom.xml)을 먼저 복사해서 받아 둔다.
# 소스만 바뀌면 이 단계는 캐시를 재사용해서 빌드가 빨라진다.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline

# 소스를 복사해서 jar를 만든다.
# 테스트는 CI(ci.yml)에서 이미 돌렸으니 여기서는 건너뛴다.
COPY src src
RUN ./mvnw -B -q package -DskipTests \
 && cp target/*.jar app.jar

# ---------- 2단계: 실행 (JRE만) ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# root가 아닌 사용자로 실행 (컨테이너가 뚫려도 피해를 줄인다)
RUN useradd --system --uid 1001 spring
RUN mkdir -p /data && chown spring /data
USER spring

COPY --from=build /app/app.jar app.jar

# 서울 시간 기준 (시간대별 기준선)
ENV TZ=Asia/Seoul
EXPOSE 8095

ENTRYPOINT ["java", "-jar", "app.jar"]
