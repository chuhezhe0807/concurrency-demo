# ============================================================================
# Dockerfile：一份“怎么把代码打包成一个可运行镜像”的说明书。
# docker build 会从上到下逐条执行，每条指令生成镜像的一“层”(layer)。
#
# 这里用“多阶段构建”(multi-stage)：分两个阶段，
#   阶段一(build)：体积大的 maven 镜像，负责把源码编译、打成 jar；
#   阶段二(运行)：体积小的 JRE 镜像，只拷贝阶段一产出的 jar 来运行。
# 好处：编译用的 maven、源码、.m2 依赖都留在阶段一，最终镜像只含“能跑的 jar + JRE”，又小又干净。
# ============================================================================

# ---------- 阶段一：编译打包 ----------

# FROM = 以哪个基础镜像为起点。maven:3.9-eclipse-temurin-21 = 预装了 Maven 3.9 + JDK 21 的官方镜像。
# AS build = 给这个阶段起名叫 build，后面阶段二要用 --from=build 引用它的产物。
FROM maven:3.9-eclipse-temurin-21 AS build

# WORKDIR = 设定“当前工作目录”，相当于 cd /build（不存在会自动创建）。
# 之后的 COPY / RUN 里写的相对路径，都相对于这个目录。
WORKDIR /build

# COPY 宿主机路径 镜像内路径 = 把构建上下文里的文件拷进镜像。
# 构建上下文是仓库根（docker-compose.yml 里写了 context: .），所以这里能看到所有模块。
#
# 为什么先单独拷 pom、后面才拷 src？——为了利用 Docker 的“层缓存”：
# Docker 发现某条指令的输入没变，就直接复用上次缓存的层，不重新执行。
# pom.xml（依赖清单）不常变，把“下载依赖”绑在 pom 这一层；只改 Java 代码时，
# 依赖层命中缓存、不必重新下载，构建快很多。
#
# 这是父 pom（多模块聚合），./ 表示拷到当前工作目录 /build 下。
COPY pom.xml ./
# 下面拷全部三个子模块的 pom：父 pom 里 <modules> 列了三个模块，
# Maven 启动时会校验每个模块都存在，少一个就报错，所以三个 pom 都得拷进来。
COPY gateway-service/pom.xml gateway-service/pom.xml
COPY product-service/pom.xml product-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml

# 到这里依赖清单齐了，再拷本服务的源码（只拷 gateway 的，改它的代码不影响别的模块的缓存层）。
COPY gateway-service/src gateway-service/src

# RUN = 在构建镜像时执行一条命令（这里就是真正的编译打包）。
# --mount=type=cache,target=/root/.m2 = 给 Maven 本地仓库(/root/.m2)挂一个“缓存盘”，
#   下载过的依赖 jar 存在这里，跨多次构建复用，不必每次都重新从网上下。
# 结尾的 \ = 续行符，表示这条命令还没写完，接下一行（只是为了排版换行）。
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl gateway-service -am clean package -DskipTests
#   mvn          = 运行 Maven
#   -B           = batch 批处理模式，不输出交互式进度条，日志更干净
#   -pl gateway-service = 只构建 gateway-service 这一个模块（pl = projects list）
#   -am          = also make，顺带把它依赖的模块（这里是父 pom）一起构建
#   clean        = 先清掉上次的 target 目录
#   package      = 编译 + 打包成 jar（Spring Boot 插件会打成可直接 java -jar 的“胖 jar”）
#   -DskipTests  = 跳过单元测试，加快构建（部署镜像时测试一般在 CI 阶段已跑过）
# 打包产物在 /build/gateway-service/target/gateway-service-1.0-SNAPSHOT.jar

# ---------- 阶段二：运行 ----------

# 重新 FROM 一个新基础镜像 = 开启阶段二。这里只要 JRE（运行时），不含编译器/Maven，体积小得多。
# 没写 AS，它就是最终输出的镜像。
FROM eclipse-temurin:21-jre

# 运行阶段的工作目录，jar 会放这里，容器启动也在这里。
WORKDIR /app

# 关键的一步：--from=build 表示“从阶段一(build)里拷文件”，而不是从宿主机。
# 把阶段一打好的 jar 拷过来并重命名为 app.jar。
# 用通配符 *-*.jar 是为了不写死版本号（1.0-SNAPSHOT 变了也不用改 Dockerfile），
# 它只匹配到那个胖 jar，不会匹配到 *.jar.original（打包前的原始 jar）。
COPY --from=build /build/gateway-service/target/gateway-service-*.jar app.jar

# EXPOSE = 声明容器对外监听 8090 端口（网关端口）。
# 注意：这只是“文档/声明”作用，真正把端口映射到宿主机要靠 docker-compose 里的 ports: "8090:8090"。
EXPOSE 8090

# ENTRYPOINT = 容器启动时执行的命令，也就是“这个容器跑起来到底干嘛”。
# 用数组(exec)形式 ["java","-jar","app.jar"]：直接以 java 进程为容器主进程，
# 收到停止信号(SIGTERM)能正确传给 Java，优雅退出。等价于命令行 `java -jar app.jar`。
ENTRYPOINT ["java", "-jar", "app.jar"]
