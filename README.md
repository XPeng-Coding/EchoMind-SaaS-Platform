# EchoMind SaaS Platform

EchoMind 是一个面向企业 SaaS 产品支持场景演进的智能客服平台。项目采用 Java/Spring Boot 后端与 Vue 3 前端，保留多智能体路由、知识库检索、会话记忆、回答校验和评测能力，支持后续扩展产品咨询、技术支持、账户与订阅管理等企业服务场景。

> 当前仓库包含 Java 后端和 Vue 前端；Python 版本不在本仓库中。前端仍保留对 Python/Java 两种后端响应格式的兼容适配。

## 项目特点

- **多 Agent 协作**：根据用户意图在通用、技术支持、账单/账户等 Agent 之间路由，并支持人工升级。
- **RAG 知识库问答**：支持查询改写、并行召回、混合检索、重排、超时、熔断和降级。
- **多轮会话记忆**：使用 Redis 保存工作记忆，使用本地 JSON 文件保存长期记忆和知识库数据。
- **回答质量校验**：对回答进行可信度、知识库依据和人工转接判断。
- **可重复评测**：保留意图识别、Macro-F1、LLM Judge、baseline 对比和异步评测任务。
- **可观测性**：提供 Actuator、Micrometer、Prometheus 指标、调用链路记录和监控摘要。
- **前后端分离**：Vue 前端支持聊天调试、健康检查、知识库管理、监控和评测页面。
- **可部署**：提供 Docker Compose、Redis、ChromaDB、Prometheus 和 Nginx 配置。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot、Spring AI、LangChain4j |
| 模型 | Anthropic、DeepSeek |
| 检索与记忆 | Hybrid RAG、Redis、本地 JSON 存储 |
| 评测与监控 | Macro-F1、LLM Judge、Spring Boot Actuator、Micrometer、Prometheus |
| 前端 | Vue 3、Vite、JavaScript |
| 部署 | Docker、Docker Compose、Nginx |

## 项目结构

```text
EchoMind 企业 SaaS 智能支持平台/
├── EchoMindJava/                 # Java/Spring Boot 后端
│   ├── src/main/java/             # 对话、Agent、RAG、记忆、评测和监控
│   ├── skills/                    # Agent Skill 与领域提示词
│   ├── config/                    # Prometheus、Nginx 等配置
│   ├── .env.example               # 本地配置模板
│   └── README.md                  # Java 后端详细说明
├── EchoMindFrontend/              # Vue 3 + Vite 前端
│   ├── src/                       # 页面、组件和后端适配
│   ├── docker/                    # Nginx 与运行时配置
│   └── README.md                  # 前端详细说明
├── .gitignore
└── README.md
```

## 快速开始

### 1. 启动 Java 后端

环境要求：

- JDK 21 或更高版本
- Docker Desktop（用于启动 Redis 等依赖）
- Anthropic 或 DeepSeek 的 API Key；也可以开启本地降级回复

进入后端目录：

```powershell
cd EchoMindJava
Copy-Item .env.example .env
docker compose up -d redis chromadb
```

选择一个模型启动。以 DeepSeek 为例：

```powershell
$env:SPRING_PROFILES_ACTIVE="deepseek"
$env:DEEPSEEK_API_KEY="your_key"
.\mvnw.cmd spring-boot:run
```

后端地址：

```text
http://localhost:8080
```

健康检查和接口文档：

```text
http://localhost:8080/health
http://localhost:8080/docs
```

### 2. 启动前端

另开终端进入前端目录：

```powershell
cd EchoMindFrontend
npm install
npm run dev
```

访问：

```text
http://localhost:5173
```

前端默认将 Java 后端请求转发到 `http://localhost:8080`。如需修改地址，可配置 `VITE_JAVA_API_URL`。

### 3. Docker 部署

后端和前端目录分别提供 Docker 配置，具体端口和服务说明见：

- [Java 后端说明](./EchoMindJava/README.md)
- [前端说明](./EchoMindFrontend/README.md)

## 核心接口

| 方法 | 接口 | 说明 |
| --- | --- | --- |
| GET | `/health` | 健康检查 |
| POST | `/chat` | 多 Agent 智能对话 |
| POST | `/search` | 知识库检索 |
| POST | `/knowledge/add` | 添加知识库文档 |
| POST | `/knowledge/upload` | 上传知识库文件 |
| GET | `/monitor` | 查看监控摘要 |
| POST | `/eval/jobs` | 提交异步评测任务 |
| GET | `/eval/jobs/{jobId}` | 查询评测任务 |
| POST | `/eval/run` | 运行兼容评测接口 |

## 企业 SaaS 场景扩展方向

当前实现以通用客服、技术支持、账单/账户支持和人工升级为基础。面向企业 SaaS 产品支持，后续可以沿着以下边界扩展：

1. 在 `IntentCategory` 中增加产品功能咨询、API 对接、订阅管理、权限申请和工单流转等意图。
2. 新增对应 Agent 和 `skills/` 领域 Skill，控制角色 Prompt、工具范围和交接条件。
3. 按产品、版本和租户导入知识库，隔离不同企业的文档与检索范围。
4. 注册路由、领域打分和评测样例，使用评测结果验证扩展前后的准确率和回答质量。
5. 接入 SaaS 产品 API、工单系统和账户/订阅服务时，增加权限校验、租户隔离和高风险操作人工确认。

## 评测

评测模块保留在 Java 后端中，用于验证意图识别和端到端回答质量：

```powershell
curl.exe -X POST http://localhost:8080/eval/run `
  -H "Content-Type: application/json" `
  -d '{}'
```

异步评测推荐使用 `/eval/jobs`，再通过任务 ID 查询进度和最终报告。评测数据、baseline 和详细实现请查看 `EchoMindJava/src/main/java/com/echomind/evaluation/`。

## 配置与安全

- 不要把真实 API Key、Redis 密码或本地 `.env` 文件提交到 Git。
- 使用 `EchoMindJava/.env.example` 复制生成本地 `.env`。
- 生产环境需要补充登录认证、租户隔离、权限控制、敏感信息脱敏和外部工具调用审计。
- 当前项目定位为可运行的技术项目原型，接入真实企业系统前需要进一步完善上述安全边界。

## 相关文档

- [Java 后端 README](./EchoMindJava/README.md)
- [Vue 前端 README](./EchoMindFrontend/README.md)

