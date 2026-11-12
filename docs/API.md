# 青龙接口速查（客户端实际用到的部分）

所有接口都返回统一包装：`{"code": 200, "data": ...}`，非 200 时 `message` 里是原因。
鉴权统一用请求头 `Authorization: Bearer <token>`。

`/open/*` 是 `/api/*` 的别名——服务端把 `/open/*` 重写到 `/api/*`，只是鉴权来源不同（OpenAPI 用应用 token）。所以两种登录方式拿到 token 后，接口路径可以完全一样。

## 登录

| 用途 | 请求 |
|---|---|
| 账号密码登录 | `POST /api/user/login`，body `{"username":"","password":""}` → `data.token` |
| 两步验证登录 | `PUT /api/user/two-factor/login`，body `{"code":"","username":"","password":""}` |
| 登出 | `POST /api/user/logout` |

登录返回码：

| code | 含义 |
|---|---|
| 200 | 成功，`data.token` |
| 401 | 用户名或密码错误 |
| 403 | IP 在黑名单 |
| 410 | 失败次数过多，`data` 是还需等待的秒数 |
| 420 | 该面板开了两步验证，需要用 `/two-factor/login` 再提交 6 位验证码（此时 `message` 为空，容易误判成“什么都没返回”） |
| 450 | 面板尚未初始化 |

## OpenAPI 应用接入

| 用途 | 请求 |
|---|---|
| 取 token | `GET /open/auth/token?client_id=xxx&client_secret=xxx` → `data.token`（`data` 里还有 `token_type`、`expiration`） |

- 青龙 2.24 起参数名是 `client_id` / `client_secret`，旧版是 `appid` / `appsecret`（2.24 上再用 `appid` 会返回 400 且提示 `"client_id" is required`）
- 应用在面板「应用管理」里创建

## 任务

| 用途 | 请求 |
|---|---|
| 列表 | `GET /api/crons?searchValue=&page=1&size=200` → `data.data[]`、`data.total` |
| 新建任务 | `POST /api/crons`，body `{"name","command","schedule"}`（command、schedule 必填） |
| 编辑任务 | `PUT /api/crons`，body `{"id","name","command","schedule"}` |
| 运行 / 停止 | `PUT /api/crons/run` / `stop`，body **纯数组** `[1,2]` |
| 启用 / 停用 | `PUT /api/crons/enable` / `disable`，body **纯数组** `[...]` |
| 删除任务 | `DELETE /api/crons`，body **纯数组** `[...]` |
| 脚本上传 | `POST /api/scripts`，multipart：`filename`(必填) + `path`(可空) + `file`(二进制) |
| 日志 | `GET /api/crons/{id}/log?tail=true&limit=200000` |

任务字段口径：

| 字段 | 说明 |
|---|---|
| `isDisabled` | 0 启用 / 1 停用 |
| `status` | 0 运行中 / 1 空闲 |
| `last_execution_time` | **秒级**时间戳（不是毫秒） |
| `last_running_time` | 上次执行**耗时**（秒），不是时间 |
| `log_path` | 日志相对路径 |

## 环境变量

| 用途 | 请求 |
|---|---|
| 列表 | `GET /api/envs?searchValue=` → `data[]` |
| 启用 / 停用 | `PUT /api/envs/enable` / `PUT /api/envs/disable`，body `{"ids":[...]}` |
| 新增 | `POST /api/envs`，body **数组** `[{"name","value","remarks"}]`，value 必须非空 |
| 修改 | `PUT /api/envs`，body `{"id","name","value","remarks"}` |
| 删除 | `DELETE /api/envs`，body **纯数组** |
| 启停 | `PUT /api/envs/enable|disable`，body **纯数组** |

变量字段：`name`、`value`、`remarks`、`status`（0 正常 / 1 禁用）。

## 系统信息

`GET /api/system` → `data` 里有 `version`、`branch`、`publishTime`、`isInitialized` 等。
注意 2.24 上这个接口是**公开**的，返回内容不包含鉴权信息，别把它当成登录态校验。

## 解析上的坑

1. **列表包装层不固定**：任务列表可能是 `data: [...]`，也可能是 `data: {data: [...], total: n}`，取数组要把两层都试。
2. **日志类型不固定**：`data` 既可能是整段字符串，也可能是按行拆的数组。
3. **时间单位混用**：同一份数据里 `last_execution_time` 是秒级时间戳，`last_running_time` 是秒数耗时。判断方法：小于 `1e8` 的值不可能是时间戳。
4. **错误码不等于 HTTP 状态**：青龙习惯 HTTP 200 + `code` 字段表达业务错误（401/420/410 都见过），只看 HTTP 状态会漏。

## 本地存储（SharedPreferences `ql`）

| key | 内容 |
|---|---|
| `base` / `mode` / `user` / `pwd` / `openId` / `openSecret` | 上次登录的地址、方式与凭据（密码类仅在勾选「记住」时保存） |
| `token` | 登录态，勾选「记住」时保存 |
| `theme` | `dark` / `light` |
| `hideHost` | 顶部地址是否遮蔽 |
| `servers` | 历史服务器 JSON 数组，元素 `{base, mode, account, secret, ts}`，最多 8 条 |
