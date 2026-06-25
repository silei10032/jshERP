# mogooErp & MogooUtils 部署 Runbook（腾讯云 Ubuntu 22.04）

> 本文档面向首次部署。一次性按章节顺序执行；后续更新代码只需走"第 4 节 常用运维操作"。
>
> 部署目标：把 **MogooUtils** 和 **mogooErp** 两个项目部署到同一台腾讯云 ECS，
> MySQL/Redis 装在 host、应用走 docker，端口直达访问（暂不上 nginx/SSL）。

---

## 部署参数速查

| 项 | 值 |
|---|---|
| 服务器规格 | 腾讯云 ECS · Ubuntu Server 22.04 LTS 64位 · 标准型 SA5 · 4C8G · 50G SSD |
| 部署用户 | `mogoo` |
| SSH 端口 | `22000`（非默认） |
| MySQL | 8.4 LTS · host 直装 · 127.0.0.1:3306 · 用 root 账号 |
| Redis | 8.8 · host 直装 · 127.0.0.1:6379 · requirepass 强密码 |
| 项目代码目录 | `/opt/MogooUtils`、`/opt/mogooErp` |
| 文件持久化目录 | `/data/mogoo-erp/upload`、`/data/mogoo-erp/export` |
| 时区 | Asia/Shanghai |
| **公网访问入口** | `http://<公网IP>:8082`（MogooUtils）<br>`http://<公网IP>:8091`（mogooErp） |
| 测试账号 | mogooErp: `jsh` / `123456` |

### 端口分配表

| 项目 | 后端 | 前端 | 备注 |
|---|---|---|---|
| MogooUtils | 8081 | **8082** | 暴露给公网的是 8082 |
| mogooErp | 8090 | **8091** | 暴露给公网的是 8091 |
| freshprice / MogooApi | 暂不部署 | — | 后续再上 |

### 腾讯云安全组规则

| 端口 | 来源 | 用途 |
|---|---|---|
| 22000 | **你的家庭 IP/32（白名单）** | SSH |
| 8082 | 0.0.0.0/0 | MogooUtils 前端 |
| 8091 | 0.0.0.0/0 | mogooErp 前端 |
| 8081 / 8090 | （不开放） | 后端 API 仅供 nginx 反代访问 |
| 3306 / 6379 | （不开放） | MySQL/Redis 仅本机 docker 访问 |
| 22 默认 SSH | （不开放） | 已改 22000 |

---

## 阶段 0 · 本地 + 腾讯云控制台准备

### 0.1 本地：生成 SSH 密钥对（在你 Mac 上跑）

```bash
# 生成专用密钥（推荐 ed25519）
ssh-keygen -t ed25519 -f ~/.ssh/tencent_mogoo -C "mogoo@tencent-cloud"
# 一路回车（passphrase 可选）

# 加到 SSH config 方便后续登录
cat >> ~/.ssh/config <<'EOF'

Host tencent-mogoo
    HostName <填公网IP>
    Port 22000
    User mogoo
    IdentityFile ~/.ssh/tencent_mogoo
    IdentitiesOnly yes
EOF

# 查看公钥，待会要贴到服务器
cat ~/.ssh/tencent_mogoo.pub
```

### 0.2 腾讯云控制台

1. 购买 ECS（Ubuntu 22.04 + 4C8G + 50G SSD）
2. 安全组按上表配置
3. 记下 **公网 IP**，填到 `~/.ssh/config` 的 HostName 处

---

## 阶段 1 · 服务器初始化（一次性 · 约 30 分钟）

### 1.1 首次登录 + SSH 加固

```bash
# 本机：用控制台设置的初始密码登录 ubuntu 用户
ssh ubuntu@<公网IP>
# 此时是 22 端口，root 密码登录

# 创建部署用户 mogoo
sudo adduser mogoo
sudo usermod -aG sudo mogoo

# 把你的公钥放到 mogoo 用户下
sudo mkdir -p /home/mogoo/.ssh
echo "<把 ~/.ssh/tencent_mogoo.pub 内容粘贴这里>" | sudo tee /home/mogoo/.ssh/authorized_keys
sudo chown -R mogoo:mogoo /home/mogoo/.ssh
sudo chmod 700 /home/mogoo/.ssh
sudo chmod 600 /home/mogoo/.ssh/authorized_keys

# 修改 SSH 配置
sudo sed -i 's/^#*Port .*/Port 22000/' /etc/ssh/sshd_config
sudo sed -i 's/^#*PermitRootLogin .*/PermitRootLogin no/' /etc/ssh/sshd_config
sudo sed -i 's/^#*PasswordAuthentication .*/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo systemctl restart sshd
exit

# 本机：用新配置测试登录
ssh tencent-mogoo
# 进入后 whoami 应输出 mogoo
```

> ⚠️ 如果改了 SSH 端口后连不上，登录腾讯云控制台 → ECS → 远程连接（VNC）→ 改回配置。
> 安全组别忘了开 22000。

### 1.2 系统更新 + 时区

```bash
sudo apt update && sudo apt full-upgrade -y
sudo timedatectl set-timezone Asia/Shanghai
sudo apt install -y curl wget vim git unzip ca-certificates gnupg
```

### 1.3 装 Docker

```bash
sudo apt install -y docker.io docker-compose-plugin
sudo usermod -aG docker $USER
exit   # 退出重登才能让 docker 组生效

# 本机重新登录
ssh tencent-mogoo
docker version
docker compose version
```

### 1.4 装 MySQL 8.4（官方 APT 源）

```bash
# 下载并安装 APT 配置包
wget https://dev.mysql.com/get/mysql-apt-config_0.8.33-1_all.deb
sudo dpkg -i mysql-apt-config_0.8.33-1_all.deb
# 弹出菜单：
#   "MySQL Server & Cluster" → 选 "mysql-8.4-lts"
#   "MySQL Tools & Connectors" → 留默认
#   最后选 "OK" 退出

sudo apt update
sudo apt install -y mysql-server

# 设 root 密码 + 加固
sudo mysql_secure_installation
# 提示：
#   Validate password component → N（你可以选 Y 上强密码策略，但 root12345 这种就过不去了）
#   New root password → 填强密码
#   Remove anonymous users → Y
#   Disallow root login remotely → Y
#   Remove test database → Y
#   Reload privilege tables → Y

# 验证只监听本机
sudo ss -tlnp | grep 3306
# 应只见 127.0.0.1:3306 / [::1]:3306，不见 0.0.0.0:3306

# 验证版本
mysql -u root -p -e "SELECT VERSION();"
# 输出 8.4.x
```

### 1.5 装 Redis 8.8（官方 APT 源）

```bash
# 加 Redis 官方源
curl -fsSL https://packages.redis.io/gpg | sudo gpg --dearmor -o /usr/share/keyrings/redis-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/redis-archive-keyring.gpg] https://packages.redis.io/deb $(lsb_release -cs) main" \
  | sudo tee /etc/apt/sources.list.d/redis.list

sudo apt update
apt-cache madison redis-server | head -5   # 看可用版本（应有 8.8.x）
sudo apt install -y redis-server

# 设密码 + 限制监听
sudo sed -i 's|^# requirepass .*|requirepass <你的Redis密码>|' /etc/redis/redis.conf
sudo systemctl restart redis-server
sudo systemctl enable redis-server

# 验证
redis-cli -a '<你的Redis密码>' ping     # 输出 PONG
redis-cli -a '<你的Redis密码>' INFO server | grep redis_version
# 输出 redis_version:8.8.x

# 检查只监听本机
sudo ss -tlnp | grep 6379
# 应只见 127.0.0.1:6379
```

### 1.6 阶段 1 验收清单

```bash
docker version              # 28.x
docker compose version      # v2.x
mysql --version             # 8.4.x
redis-cli --version         # 8.8.x
sudo ss -tlnp | grep -E "3306|6379"
# 都应仅 127.0.0.1
```

---

## 阶段 2 · 部署 MogooUtils（约 15 分钟）

### 2.1 准备数据库

```bash
sudo mysql -u root -p <<'EOF'
CREATE DATABASE canteen_delivery DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
EOF
```

### 2.2 拉代码

```bash
sudo mkdir -p /opt/MogooUtils && sudo chown mogoo:mogoo /opt/MogooUtils
cd /opt
git clone https://github.com/silei10032/MogooUtils.git
# 如果用 ssh 方式，先把服务器上的 ~/.ssh/id_ed25519.pub 加到 github deploy keys
cd MogooUtils
```

### 2.3 导入初始 SQL

```bash
mysql -u root -p canteen_delivery < mysql/init/init.sql
# 验证表已建：
mysql -u root -p canteen_delivery -e "SHOW TABLES;"
```

### 2.4 配置 .env

```bash
cp .env.example .env
vi .env
# 填入：
#   MYSQL_ROOT_PASSWORD=<MySQL root 密码>
```

### 2.5 启动

```bash
docker compose up -d --build
# 首次 build 5-10 分钟（拉 maven / npm 依赖）

# 跟踪后端启动日志
docker compose logs -f app
# 看到 "Started ..." 字样即成功，Ctrl+C 退出 follow
```

### 2.6 验收

```bash
# 服务器本地
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8082/
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8081/

# 你的电脑
浏览器开 http://<公网IP>:8082
登录 → 能正常使用 → 阶段 2 完成
```

---

## 阶段 3 · 部署 mogooErp（约 15 分钟）

### 3.1 准备数据库

```bash
sudo mysql -u root -p <<'EOF'
CREATE DATABASE mogoo_erp DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
EOF
```

### 3.2 拉代码

```bash
sudo mkdir -p /opt/mogooErp && sudo chown mogoo:mogoo /opt/mogooErp
cd /opt
git clone https://github.com/silei10032/jshERP.git mogooErp
cd mogooErp
```

### 3.3 导入初始 SQL

```bash
mysql -u root -p mogoo_erp < backend/docs/jsh_erp.sql
mysql -u root -p mogoo_erp -e "SHOW TABLES;" | head
```

### 3.4 准备文件持久化目录

```bash
sudo mkdir -p /data/mogoo-erp/upload /data/mogoo-erp/export
sudo chown -R mogoo:mogoo /data/mogoo-erp
```

### 3.5 配置 .env

```bash
cp .env.example .env
vi .env
# 必填：
#   DB_PASSWORD=<MySQL root 密码>
#   REDIS_PASSWORD=<Redis 密码>
# 推荐覆盖：
#   REDIS_DB=1
#   LOG_LEVEL_APP=INFO
#   UPLOAD_DIR=/data/mogoo-erp/upload
#   EXPORT_DIR=/data/mogoo-erp/export
#   JAVA_OPTS=-Xms512m -Xmx1024m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai
```

### 3.6 启动

```bash
docker compose up -d --build
docker compose logs -f app
# 看到 "Started ErpApplication" 即成功，Ctrl+C 退出
```

### 3.7 验收

```bash
# 服务器本地
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8091/
curl -s -o /dev/null -w "HTTP %{http_code}\n" "http://localhost:8091/mogoo-erp/user/randomImage?_t=1"
curl -s http://localhost:8090/mogoo-erp/v3/api-docs | head -c 100   # springdoc

# 你的电脑
浏览器开 http://<公网IP>:8091
用 jsh / 123456 登录
点几个单据列表（采购订单 / 销售订单 / 零售出库），确认能加载数据 → 阶段 3 完成
```

---

## 4. 常用运维操作

### 看日志

```bash
cd /opt/mogooErp && docker compose logs -f app          # 后端
cd /opt/mogooErp && docker compose logs -f frontend     # 前端 nginx
```

### 重启

```bash
cd /opt/mogooErp && docker compose restart app          # 仅后端
cd /opt/mogooErp && docker compose down && docker compose up -d  # 全部
```

### 更新代码

```bash
cd /opt/mogooErp
git pull
docker compose up -d --build      # 增量 build，maven/npm 缓存层会复用
```

### 看资源占用

```bash
docker stats --no-stream
free -h
df -h
```

### 数据库手动备份

```bash
mkdir -p ~/backups
mysqldump -u root -p mogoo_erp | gzip > ~/backups/mogoo_erp_$(date +%Y%m%d_%H%M%S).sql.gz
mysqldump -u root -p canteen_delivery | gzip > ~/backups/canteen_delivery_$(date +%Y%m%d_%H%M%S).sql.gz
```

---

## 5. 故障排查（已知坑速查）

| 现象 | 排查 |
|---|---|
| `docker compose up` 报 `DB_PASSWORD 必填` | `.env` 没填 `DB_PASSWORD` |
| 后端容器 `Restarting (1)`，logs 看到 `ClassNotFoundException: ch.qos.logback.core.rolling.SizeAndTimeBasedFNATP` | 拉的不是最新代码，`git pull` 同步到 commit `aa59c60d` 之后 |
| 后端 `APPLICATION FAILED TO START ... beans form a cycle` | 同上，最新代码已加 `allow-circular-references=true` |
| 浏览器列表请求全 400 | Tomcat 严格 RFC，最新代码已加 `relaxed-query-chars` |
| `/swagger-ui/index.html` 返回 500 + "loginOut" | LogCostFilter 白名单老路径，最新代码已修 |
| 容器内 ping `host.docker.internal` 失败 | docker-compose.yml 没有 `extra_hosts: host-gateway`，最新代码已加 |
| 前端 build 时 npm peer dep ERESOLVE | frontend/Dockerfile 应有 `--legacy-peer-deps`，最新代码已加 |
| `docker compose up` 后 app 容器 `Exit 1` | 看 `docker compose logs app`，绝大多数情况是 .env 或数据库未初始化 |

---

## 6. 推迟事项（不在本次部署范围）

到了需要时再做：

- **域名 + Let's Encrypt SSL**：host 装 nginx + certbot，把 docker compose 端口绑定改成 `127.0.0.1:8091:80`、关闭安全组 8082/8091、只留 80/443
- **自动备份**：cron + `mysqldump` + rsync 到腾讯云 COS
- **freshprice / MogooApi 部署**：相同模板套用，端口分配见上表
- **监控告警**：腾讯云监控 ECS 基础指标够用，业务层面可后续加 Prometheus

---

## 附录 · 关键 commit 速查

| 仓库 | commit | 说明 |
|---|---|---|
| mogooErp | `aa59c60d` | 新增 docker 部署配置 + 端口规划 8090/8091 |
| mogooErp | `b1f5f0d7` | context-path 改名 /mogoo-erp + Boot 3 运行时兼容修复 |
| MogooUtils | `0441b7b` | docker-compose 加 extra_hosts + `:?required` 保护 |

服务器拉代码时只要 `git pull` 到包含以上提交的版本，6 个 Boot 3 兼容洞就都已修过。
