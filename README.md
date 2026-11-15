# OpenTelemetry + Jaeger 调用链实验室

一个浏览器前端、两个 Java 21 / Spring Boot 微服务，附 Docker Compose 和 Kubernetes 部署。

## 架构与 span

业务请求：浏览器 → Nginx（同源代理）→ order-service → inventory-service。

数据上报：浏览器 SDK → Nginx `/otel/v1/traces` → Jaeger；两个 Java agent → Jaeger OTLP/HTTP 4318。
Nginx 不埋点，不生成独立 span。这个示例直接将 OTLP 发往 Jaeger，没有单独的 OpenTelemetry Collector。

预期同一个 Trace 内包含：

- `web-frontend`: `POST /api/orders`（浏览器真实 client span）
- `order-service`: HTTP server span → `order.create` → HTTP client span
- `inventory-service`: HTTP server span → `inventory.lookup`

HTTP server/client span 由 OpenTelemetry Java agent 自动创建；业务 span 使用 OpenTelemetry API 创建。
浏览器显式 inject W3C `traceparent`；Java agent 自动提取并继续传播。前端不是随机构造一个 Trace ID，而是使用完整 SDK 上报 span。

## 目录结构

| 根目录 | 内容 |
| --- | --- |
| `frontend/` | 前端源码与 npm 依赖锁定文件 |
| `order-service/` | Java 订单微服务源码 |
| `inventory-service/` | Java 库存微服务源码 |
| `deployment/` | Dockerfile、Nginx 配置、Compose、K8s manifests 和部署验证脚本 |
| `pom.xml` | 两个 Java 服务共用的 Maven 父项目 |

所有命令从仓库根目录执行。

## 1. Docker Compose 快速运行

需要 Docker Engine / Docker Desktop 和 Compose v2；首次构建需能访问 Maven Central、npm 和镜像仓库。

```bash
docker compose -f deployment/compose.yaml up -d --build
docker compose -f deployment/compose.yaml logs -f order-service inventory-service
```

等待 Spring Boot 启动完成后打开：

- 前端：http://localhost:8080
- Jaeger：http://localhost:16686

页面有正常、慢请求、异常请求按钮。响应显示 Trace ID，并可跳转至 Jaeger。Java span 批量导出有几秒延迟，刚跳转没有数据时稍等再刷新。
停止：`docker compose -f deployment/compose.yaml down`。

## 2. Kubernetes：本地 kind

需要 Docker、kind、kubectl。以下命令都在项目根目录执行。示例创建名为 `otel-demo` 的独立集群；已有集群可自行跳过创建并使用对应名称。

```bash
kind create cluster --name otel-demo
bash deployment/scripts/build-images.sh
kind load docker-image --name otel-demo \
  otel-demo/order-service:1.0.0 \
  otel-demo/inventory-service:1.0.0 \
  otel-demo/frontend:1.0.0
kubectl apply -k deployment/k8s
kubectl -n otel-demo rollout status deployment/jaeger --timeout=300s
kubectl -n otel-demo rollout status deployment/inventory-service --timeout=300s
kubectl -n otel-demo rollout status deployment/order-service --timeout=300s
kubectl -n otel-demo rollout status deployment/frontend --timeout=300s
```

在两个终端分别运行并保持开启：

```bash
kubectl -n otel-demo port-forward svc/frontend 8080:8080
```

```bash
kubectl -n otel-demo port-forward svc/jaeger 16686:16686
```

打开 http://localhost:8080 和 http://localhost:16686 。使用 port-forward 不需要 Ingress Controller、NodePort 或 LoadBalancer。
如果 kubectl 在远程主机运行，使用 SSH 本地端口转发，例如 `ssh -L 8080:127.0.0.1:8080 -L 16686:127.0.0.1:16686 user@host`，随后在本机访问。

所有资源都在 `otel-demo` namespace：4 个 Deployment、4 个 ClusterIP Service、1 个 ConfigMap。
应用镜像使用 `IfNotPresent`，配置了资源 requests/limits、启动探针、就绪探针、存活探针。前端配置已打包进镜像。

## 3. Kubernetes：已有集群 / 私有镜像仓库

将以下 `registry.example.com/team/otel-demo` 替换成你的仓库路径，先执行 `docker login`。

```bash
export IMAGE_PREFIX=registry.example.com/team/otel-demo
export IMAGE_TAG=1.0.0
bash deployment/scripts/build-images.sh
docker push "$IMAGE_PREFIX/order-service:$IMAGE_TAG"
docker push "$IMAGE_PREFIX/inventory-service:$IMAGE_TAG"
docker push "$IMAGE_PREFIX/frontend:$IMAGE_TAG"
```

修改 `deployment/k8s/kustomization.yaml` 的三个 `newName` 为上述完整镜像名（不带 tag），`newTag` 为你推送的版本。例如：

```yaml
images:
  - name: otel-demo/order-service
    newName: registry.example.com/team/otel-demo/order-service
    newTag: 1.0.0
  - name: otel-demo/inventory-service
    newName: registry.example.com/team/otel-demo/inventory-service
    newTag: 1.0.0
  - name: otel-demo/frontend
    newName: registry.example.com/team/otel-demo/frontend
    newTag: 1.0.0
```

执行 `kubectl apply -k deployment/k8s`，再按上一节检查 rollout 并 port-forward。私有仓库需要为 namespace 中的 Pod 配置 `imagePullSecrets`，或使用集群已配置的仓库认证。
构建机器与节点 CPU 架构不同时，应使用 Docker buildx 为目标平台构建并 push；本脚本默认构建当前平台。

可选：需要局域网直连前端时，在网络和防火墙允许的测试集群执行：

```bash
kubectl -n otel-demo patch service frontend --type=merge \
  -p '{"spec":{"type":"NodePort","ports":[{"name":"http","port":8080,"targetPort":"http","nodePort":30080}]}}'
```

访问 `http://节点IP:30080`。Jaeger 仍可 port-forward；页面的 Jaeger 地址需填写浏览器能访问的地址，不能填集群 Service DNS。
不要将此无认证演示应用及 OTLP 接口暴露到公网。

## 4. 测试场景与验收

| 场景 | 请求参数 | 预期结果 |
| --- | --- | --- |
| 正常 | normal | HTTP 200，库存查询约 80ms，同一 Trace 串起 3 个服务 |
| 慢请求 | slow | HTTP 200，`inventory.lookup` 约 1.8s，可定位耗时来源 |
| 异常 | error | 库存服务 503，订单服务 502，业务 span 带错误状态和 exception event |

这不是数据库测试：库存查询通过 sleep 模拟，不依赖数据库。

命令行产生后端 Trace：

```bash
curl -X POST 'http://localhost:8080/api/orders?scenario=normal'
curl -X POST 'http://localhost:8080/api/orders?scenario=slow'
curl -X POST 'http://localhost:8080/api/orders?scenario=error'
python3 deployment/scripts/verify-trace.py
```

验证脚本检查 HTTP 状态、两后端 Trace ID 一致、慢请求延迟、Jaeger 中出现两个服务及业务 span、错误 span 标签；最多等待每条 Trace 30 秒。
脚本仅使用 Python 标准库，可指定前端及 Jaeger 地址：
`python3 deployment/scripts/verify-trace.py http://localhost:8080 http://localhost:16686`。

curl 没有浏览器 span。要验收三服务链，请点击页面按钮，在 Jaeger 中确认 `web-frontend`、`order-service`、`inventory-service` 属于同一条 Trace，并查看父子关系。

## 5. 运维排查与已有 Collector 接入

```bash
kubectl -n otel-demo get pods,svc
kubectl -n otel-demo logs deployment/order-service --tail=100
kubectl -n otel-demo logs deployment/inventory-service --tail=100
kubectl -n otel-demo logs deployment/jaeger --tail=100
kubectl -n otel-demo describe pod <pod-name>
```

- `ImagePullBackOff`：核对镜像名/tag、kind load、仓库访问和认证。
- 页面返回 502：检查后端 Pod 就绪、Service endpoints、订单服务日志中的下游错误。
- 返回全零 Trace ID：Java agent 未生效；启动命令必须含 `-javaagent:/app/otel.jar`。
- 后端正常但 Jaeger 没 Trace：检查 4318 网络连通、agent 导出错误；不要把 OTLP 地址写成 UI 的 16686。
- 浏览器 span 缺失：浏览器开发者工具检查 `/otel/v1/traces`，应为成功 HTTP 响应；扩展拦截和代理错误都可能影响导出。
- 日志直接打印 traceId，并配置了 trace_id/span_id MDC，方便按链路检索。

环境变量集中在 `deployment/k8s/all.yaml` 的 `otel-config` ConfigMap（Compose 使用 x-otel）：

```text
OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_TRACES_EXPORTER=otlp
OTEL_TRACES_SAMPLER=always_on
OTEL_PROPAGATORS=tracecontext,baggage
OTEL_METRICS_EXPORTER=none
OTEL_LOGS_EXPORTER=none
```

已有 OTel Collector 时，把后端 endpoint 改为 `http://你的collector:4318`；前端 `deployment/nginx.conf` 的 `/otel/v1/traces` 上游也改为该 Collector 的 `/v1/traces` 并重建前端镜像。Collector 需要启用 OTLP HTTP receiver，并导出到 Jaeger。
修改 ConfigMap 后运行 `kubectl -n otel-demo rollout restart deployment/order-service deployment/inventory-service` 使环境变量生效。

默认 100% 采样，便于验证；Jaeger 为内存存储，没有 PVC，重启后数据丢失，仅用于实验。版本固定以便复现，不声明为最新版本；正式使用前升级依赖并增加认证、持久化和采样策略。

清理本示例全部 K8s 资源：`kubectl delete -k deployment/k8s`（包括整个 otel-demo namespace）。

## 文件说明

- `order-service/`、`inventory-service/`：Java 源码及 Maven 项目。
- `frontend/`：浏览器 OTel SDK 和页面源码。
- `deployment/Dockerfile.frontend`、`deployment/nginx.conf`：前端镜像与 Nginx 同源代理配置。
- `deployment/Dockerfile`：两个 Java 服务共用多阶段构建，使用 SERVICE 参数选择产物。
- `deployment/compose.yaml`：本地启动。
- `deployment/k8s/`：Kubernetes 清单与 Kustomize 镜像配置。
- `deployment/scripts/`：镜像构建与实际 Trace 验证脚本。

## 官方参考

- Java agent 配置：https://opentelemetry.io/docs/zero-code/java/agent/configuration/
- OTLP 配置：https://opentelemetry.io/docs/languages/sdk-configuration/otlp-exporter/
- Jaeger：https://www.jaegertracing.io/docs/2.6/getting-started/

## 本次交付验证范围

已完成前端 npm 构建、两个 Java 服务 Maven 打包、带 Java agent 的双服务实际启动与请求测试。验证正常 200、慢请求约 1.81 秒、异常 502，以及传入 W3C Trace ID 跨两个服务保持一致。K8s YAML 解析、Service selector 和 targetPort 对应检查通过。
当前执行环境缺少完整 JDK 的 ct.sym，构建验证使用等价的 Java 21 source/target 参数覆盖 release 参数；交付 Dockerfile 使用完整 JDK，无需此覆盖。
未在本环境执行 Docker 镜像构建、真实 K8s 部署或 Jaeger UI 端到端验证；请部署后运行 `deployment/scripts/verify-trace.py` 并通过页面验收浏览器 span。
