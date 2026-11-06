# 保存舞蹈练习歌单

`POST /playlist/dance/save` 使用现有登录拦截器、Redis 会话和 JWT 校验。
普通用户角色 `ROLE_USER` 可以创建新的歌单，不能通过本接口修改现有歌单。
管理员权限、公开路径和 AI 工具均未修改。

## 请求与响应

```http
POST /playlist/dance/save
Authorization: Bearer <登录后获得的用户 token>
Content-Type: application/json

{
  "title": "基础街舞练习",
  "description": "用于基础动作练习的歌曲",
  "songIds": [7, 2, 12]
}
```

- `title`：必填，不能全为空白，最多 255 个字符，保存时去掉首尾空白。
- `description`：可选，最多 2000 个字符，映射到现有的 `introduction` 字段。
- `songIds`：1–50 个不同的正整数，必须全部存在于曲库。小数、字符串和超出 Long 范围的 ID 会被拒绝。
- 遵循现有建歌单习惯，已存在的标题会被拒绝；数据库没有标题唯一约束，因此并发同名创建不作唯一性保证。
- 封面取请求中第一首真实歌曲的数据库封面地址。客户端不提供资源地址。

HTTP 200，返回数据库生成的 ID（下面的 123 仅为示例）：

```json
{"code":0,"message":"操作成功","data":123}
```

使用 `GET /playlist/getPlaylistDetail/123` 查看歌单，也可以通过已有收藏接口收藏。
本接口不会自动收藏。

输入无效、歌曲不存在、歌曲 ID 重复或标题重复返回 HTTP 400，例如：

```json
{"code":1,"message":"以下歌曲不存在：[999]","data":null}
```

没有 token、token 失效或不在 Redis 会话中返回 HTTP 401；角色无权限返回 HTTP 403。
401/403 沿用现有拦截器的响应格式。存储失败返回 HTTP 500，并触发整个保存事务的回滚。
所有歌曲在第一次写入前一次性校验；写入复用现有 PlaylistMapper、SongMapper 和 PlaylistBindingMapper。

## 当前数据库的能力限制

没有数据库迁移或新表：

- `tb_playlist` 没有 owner、user_id 或 privacy 字段。本接口创建的是全站共享、可公开浏览的歌单，不能称为用户私有歌单，也不记录创建者。
- `tb_playlist_binding` 只有 `(playlist_id, song_id)` 联合主键，没有 position 字段。虽然按请求顺序执行插入，现有详情查询没有 ORDER BY，**不能持久化或保证重新读取后的曲目顺序**。
- 如果以后需要所有者、私有访问或固定练习顺序，需要另做数据库迁移、实体及读取/授权逻辑更新；不能以插入顺序代替排序字段。
- 此接口只检查歌曲记录是否存在，不验证音频文件可下载或可播放。

## 自动化验证

从 `vibe-music-backend` 执行：

```bash
bash ./mvnw -B clean verify
```

需要完整 JDK 17+。托管云环境如需平台代理，请传入该环境已配置的 Maven settings。
测试只使用现有 Spring Test、JUnit 和 Mockito，不启动完整 Spring Boot，不要求 MySQL、Redis、MinIO、SMTP 或 DashScope。

- Controller 测试使用真实登录拦截器和权限配置，mock Redis、业务服务，验证鉴权、参数校验和 HTTP 响应。
- Service 测试使用真实 Spring 事务代理和 mock 事务管理器，验证歌曲存在性、无效输入不写入、成功提交，以及中途写失败时调用 rollback 而不调用 commit。
- mock 测试不证明真实 MySQL 存储引擎已经回滚。真实数据库保存、外键约束、缓存失效及完整登录后读取仍需集成演示。

端到端演示需要导入 `sql/vibe_music.sql`，提供现有应用所需的数据库、Redis、MinIO 等配置，启动后端，并用真实用户登录获得有效会话。
完整应用仍要求填妥现有 SMTP、DashScope 等启动属性；该保存接口本身不调用邮箱或 AI，也不上传文件。
所有凭据放入环境设置或被忽略的本地配置，不提交到仓库。
