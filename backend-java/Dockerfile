# syntax=docker/dockerfile:1
# 构建阶段：Maven + JDK 17（pom 的 release 目标为 17）
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 可选：通过构建参数使用 Maven 镜像加速（默认仍为 Maven Central）
ARG MAVEN_MIRROR=
RUN if [ -n "$MAVEN_MIRROR" ]; then mkdir -p /root/.m2 && printf '%s' "<settings><mirrors><mirror><id>build-mirror</id><mirrorOf>central</mirrorOf><url>$MAVEN_MIRROR</url></mirror></mirrors></settings>" > /root/.m2/settings.xml; fi

# 先只复制 pom，利用镜像层缓存依赖下载
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
# 镜像构建不编译测试源码；测试由 CI 的 mvnw clean package 单独执行
RUN mvn -B -Dmaven.test.skip=true package

# 运行阶段：仅 JRE，非 root 用户，日志写入可挂载目录
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/serviceflow-backend-*.jar app.jar
RUN mkdir -p /app/logs && chown -R app:app /app
USER app
EXPOSE 8080

# 容器内存限制下按比例设置堆；可通过 docker run -e JAVA_OPTS=... 覆盖
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
