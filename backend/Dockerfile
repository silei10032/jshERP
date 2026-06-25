# ---- Build Stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY docker-maven-settings.xml /root/.m2/settings.xml
COPY pom.xml .
# 预下载依赖（利用 Docker 层缓存）
RUN mvn dependency:go-offline -B
COPY src ./src
# spring-boot:repackage 后产物为 target/jshERP.jar（pom 里 <finalName>jshERP</finalName>）
RUN mvn -B -DskipTests -Dmaven.main.skip=false clean package

# ---- Runtime Stage ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/target/jshERP.jar app.jar

ENV JAVA_OPTS="-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

EXPOSE 8090
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
