# 多阶段构建：构建上下文为仓库根（docker-compose 中 context: .），自带 maven 打包，不依赖宿主机环境。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY common/pom.xml common/pom.xml
COPY gateway-service/pom.xml gateway-service/pom.xml
COPY product-service/pom.xml product-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY common/src common/src
COPY product-service/src product-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl product-service -am clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/product-service/target/product-service-*.jar app.jar
EXPOSE 8071
ENTRYPOINT ["java", "-jar", "app.jar"]
