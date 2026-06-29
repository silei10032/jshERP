# mogooErp & MogooUtils 部署 Runbook（腾讯云 Ubuntu 24.04）

> 本文档面向首次部署。一次性按章节顺序执行；后续更新代码只需走"第 4 节 常用运维操作"。
>
> 部署目标：把 **MogooUtils** 和 **mogooErp** 两个项目部署到同一台腾讯云 ECS，
> MySQL/Redis 装在 host、应用走 docker，端口直达访问（暂不上 nginx/SSL）。

---

## 部署参数速查

| 项 | 值 |
|---|---|
| 服务器规格 | 腾讯云 ECS · Ubuntu Server 24.04 LTS 64位（noble）· 标准型 SA5 · 4C8G · 50G SSD |
| 部署用户 | `ubuntu`（系统默认，省去自建用户） |
| SSH 端口 | `22`（默认，未做端口改写） |
| MySQL | 8.0（Ubuntu 自带源）· host 直装 · bind `0.0.0.0:3306` · 用 root 账号 · 外部由安全组屏蔽 |
| Redis | 8.x · host 直装 · bind `0.0.0.0:6379` · requirepass 强密码 · 外部由安全组屏蔽 |
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
| freshprice | 8080 | **8092** | 走 host nginx → `price.mogoocloud.cn` |
| MogooApi | 暂不部署 | — | 后续再上 |

### 腾讯云安全组规则

| 端口 | 来源 | 用途 |
|---|---|---|
| 22 | **你的家庭 IP/32（白名单）** | SSH（默认端口，靠 key + 源 IP 白名单防护） |
| 8082 | 0.0.0.0/0 | MogooUtils 前端 |
| 8091 | 0.0.0.0/0 | mogooErp 前端 |
| 8081 / 8090 | （不开放） | 后端 API 仅供 nginx 反代访问 |
| 3306 / 6379 | （不开放） | MySQL/Redis 仅本机 docker 访问 |

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
    Port 22
    User ubuntu
    IdentityFile ~/.ssh/tencent_mogoo
    IdentitiesOnly yes
EOF

# 查看公钥，待会要贴到服务器
cat ~/.ssh/tencent_mogoo.pub
```

### 0.2 腾讯云控制台

1. 购买 ECS（Ubuntu 24.04 + 4C8G + 50G SSD）
2. 安全组按上表配置
3. 记下 **公网 IP**，填到 `~/.ssh/config` 的 HostName 处

---

## 阶段 1 · 服务器初始化（一次性 · 约 30 分钟）

### 1.1 首次登录 + 装 SSH key

> 不创建专用部署用户、不改端口、不禁密码 —— 本机签发了 key 后从 Mac 用 key 登 ubuntu，
> 服务器层面靠安全组只放白名单 IP 来兜底。后续要更硬可以再回头加 `PasswordAuthentication no`。

```bash
# 本机：用控制台设置的初始密码登录 ubuntu 一次，把 tencent_mogoo.pub 推上去
cat ~/.ssh/tencent_mogoo.pub | ssh ubuntu@<公网IP> \
  'mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && echo OK'
# 输入 ubuntu 密码，看到 OK 即装好

# 本机：用 key 测试登录
ssh tencent-mogoo
# 进入后 whoami 应输出 ubuntu，不再问密码
```

### 1.2 装 Docker

> Ubuntu 自带源里没有 `docker-compose-plugin`，必须用 Docker 的 APT 源。
> 走该源同时拿到 `docker-ce` + `docker-compose-plugin` + `buildx`，一次到位。
>
> ⚠️ **腾讯云 ECS 必须用腾讯云镜像源**：国内网访问官方 `download.docker.com`
> 常 timeout，导致 `apt update` 静默拿不到包目录、`apt install` 报
> "Package docker-ce is not available / has no installation candidate"。
> 走 `mirrors.tencentyun.com` 不算公网流量、不收费、对 ECS 高速。
> （非腾讯云环境想用官方源，把下面两处 `mirrors.tencentyun.com/docker-ce`
> 换成 `download.docker.com` 即可。）

```bash
# 1) 装 Docker GPG key（腾讯云镜像）
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://mirrors.tencentyun.com/docker-ce/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

# 2) 加 Docker APT 源（腾讯云镜像）
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://mirrors.tencentyun.com/docker-ce/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# 3) 装 docker engine + compose plugin
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# 4) 配 Docker Hub 镜像加速（关键！否则 build 拉 nginx/node/temurin 等 base 镜像会 i/o timeout）
#    docker.io / registry-1.docker.io 在腾讯云内网不通，mirror.ccs.tencentyun.com 是 ECS 专用镜像
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json >/dev/null <<'EOF'
{
  "registry-mirrors": ["https://mirror.ccs.tencentyun.com"]
}
EOF
sudo systemctl restart docker
docker info | grep -A2 "Registry Mirrors"    # 应列出 mirror.ccs.tencentyun.com

# 5) 把 ubuntu 加到 docker 组（免 sudo 跑 docker）
#    显式写 ubuntu，别用 $USER——sudo 环境下 $USER 可能解析不到登录用户，加不进组
sudo usermod -aG docker ubuntu
getent group docker      # 确认末尾有 ubuntu，如 docker:x:999:ubuntu；没有就说明没加成功
exit   # 必须【完整重新登录】组才生效；同会话内或 newgrp 在没真正进组前都不行

# 本机重新登录
ssh tencent-mogoo
id -nG                   # 应包含 docker
docker version           # 29.x
docker compose version   # v5.x
docker ps                # 不带 sudo 能列出空表头 = 组权限 OK
```

> 排错：`docker version` 客户端能打印但报 `permission denied ... /var/run/docker.sock`，
> 就是 docker 组没生效。`newgrp docker` 反过来跟你要密码且 Invalid，说明 ubuntu 压根
> 没进组（不在组才会问那个不存在的组密码）——回去重跑上面的 `usermod` + 完整重登。

### 1.3 装 MySQL 8.0（Ubuntu 自带源）

> 用 Ubuntu 24.04 自带源的 `mysql-server`（8.0），**不走 MySQL 官方 APT**。原因：
> 官方源那把签名 key 周期性过期、`apt update` 老报 `EXPKEYSIG`，维护烦；
> 而 Ubuntu 的 8.0 由 Canonical backport 安全补丁直到 24.04 EOL（2029），
> app 也不区分 8.0/8.4。少一个第三方源、少一堆坑。
> （真要 8.4 LTS 再上 mysql-apt-config，但就要自己扛 key 过期。）

```bash
sudo apt install -y mysql-server

# 加固（删匿名用户 / test 库）。注意 Ubuntu 包装完后 root@localhost 默认是
# auth_socket 插件（无密码、靠 OS 用户认证），所以这一步【不会让你设 root 密码】，
# 只会问要不要改——auth_socket 下跳过即可，密码在下一步用 sudo mysql 设。
sudo mysql_secure_installation
# 各项怎么选：
#   VALIDATE PASSWORD component → 选 Y 后默认是 MEDIUM（要大小写+数字+特殊符号，
#     mogoo123 这种纯小写+数字过不去）；想用弱密码就选 N，或装好后 SET GLOBAL ...policy=LOW
#   Remove anonymous users → Y
#   Disallow root login remotely → Y（不影响下面单独给 docker 子网开 root）
#   Remove test database → Y
#   Reload privilege tables → Y

# 设 root 密码 + 放开 docker 子网访问（root@localhost 默认 auth_socket，sudo mysql 免密进）
#   · 把 root@localhost 从 auth_socket 改成密码认证，这样 deploy.md 里 mysql -u root -p 才能用
#   · 容器从 docker 网桥 IP（172.x）连、不算 localhost，上一步又禁了远程 root，
#     所以必须单独建 root@'172.%'，否则 §3.6 启动报 Access denied for 'root'@'172.x.x.x'
sudo mysql <<'SQL'
-- 弱密码过不了默认 MEDIUM 策略时，取消下一行注释降到 LOW（只校验长度≥8）：
-- SET GLOBAL validate_password.policy = LOW;
ALTER USER 'root'@'localhost' IDENTIFIED WITH caching_sha2_password BY '<你的root密码>';
CREATE USER IF NOT EXISTS 'root'@'172.%' IDENTIFIED WITH caching_sha2_password BY '<你的root密码>';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'172.%' WITH GRANT OPTION;
FLUSH PRIVILEGES;
SQL

# 改 bind-address 让 docker 容器能从 host.docker.internal 连进来
# 默认 bind 127.0.0.1，但 Linux 上 docker 容器走 docker 网桥 IP（如 172.17.0.1），
# 必须 bind 0.0.0.0 才能被容器访问。外部访问由腾讯云安全组（不开 3306）+ 强密码兜底。
sudo sed -i 's/^bind-address.*=.*/bind-address = 0.0.0.0/' /etc/mysql/mysql.conf.d/mysqld.cnf
sudo systemctl restart mysql

# 验证：监听 + 密码登录 + 两个 root 用户都在
sudo ss -tlnp | grep 3306
# 应见 0.0.0.0:3306（容器走 docker bridge 连）。X 协议 33060 留在 127.0.0.1 即可，没用到。
mysql -u root -p -e "SELECT VERSION();"                                          # 输密码，应打出 8.0.x
mysql -u root -p -e "SELECT user,host,plugin FROM mysql.user WHERE user='root';" # 应见 localhost + 172.% 两行
```

### 1.4 装 Redis（官方 APT 源）

```bash
# 加 Redis 官方源
curl -fsSL https://packages.redis.io/gpg | sudo gpg --dearmor -o /usr/share/keyrings/redis-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/redis-archive-keyring.gpg] https://packages.redis.io/deb $(lsb_release -cs) main" \
  | sudo tee /etc/apt/sources.list.d/redis.list

sudo apt update
apt-cache madison redis-server | head -5   # 看可用版本（应有 8.8.x）
sudo apt install -y redis-server

# 设密码 + 允许 docker 网桥访问
# bind 同 MySQL：必须 0.0.0.0，否则容器 host.docker.internal 连不上。
# 外部访问由腾讯云安全组（不开 6379）+ requirepass 兜底。
sudo sed -i 's|^# requirepass .*|requirepass <你的Redis密码>|' /etc/redis/redis.conf
echo "bind 0.0.0.0" | sudo tee -a /etc/redis/redis.conf   # 追加在文件尾，最后一条 bind 生效
sudo systemctl restart redis-server
sudo systemctl enable redis-server

# 验证
redis-cli -a '<你的Redis密码>' ping     # 输出 PONG
redis-cli -a '<你的Redis密码>' INFO server | grep redis_version
# 输出 redis_version:8.x.x

# 检查监听
sudo ss -tlnp | grep 6379
# 应见 0.0.0.0:6379（容器走 docker bridge 连）
```

### 1.5 阶段 1 验收清单

```bash
docker version              # 29.x
docker compose version      # v5.x
mysql --version             # 8.0.x（Ubuntu 自带源）
redis-cli --version         # 8.x.x
sudo ss -tlnp | grep -E "3306|6379"
# 都应见 0.0.0.0:3306 / 0.0.0.0:6379（容器走 docker bridge 连）；
# 外部访问由腾讯云安全组（不开 3306/6379）+ 强密码兜底。
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

> ⚠️ 私有仓 + 腾讯云：github 的 **HTTPS 常被 TLS 重置**（`GnuTLS recv error -110`），
> 且私有仓要鉴权。改用 **SSH over 443**（绕过 GFW 对 22 端口的封锁）+ 密钥。
> 下面的「一次性 GitHub 配置」服务器只做一次，之后所有私有仓（MogooUtils / mogooErp / …）共用。

```bash
sudo apt install -y git    # 精简镜像默认无 git；已装可跳过

# === 一次性 GitHub 配置（服务器全局，只做一次）===
ssh-keygen -t ed25519 -f ~/.ssh/github_deploy -N "" -C "mogoo-server"
cat >> ~/.ssh/config <<'EOF'

Host github.com
    HostName ssh.github.com
    Port 443
    User git
    IdentityFile ~/.ssh/github_deploy
    IdentitiesOnly yes
EOF
chmod 600 ~/.ssh/config
nc -zv ssh.github.com 443          # 先确认 443 通（国内通常能过）
cat ~/.ssh/github_deploy.pub
# ↑ 把这行公钥加到 GitHub，二选一：
#   多仓共用 → 账号 Settings → SSH and GPG keys → New SSH key（推荐，一把覆盖所有私有仓）
#   单仓只读 → 该仓 Settings → Deploy keys → Add（deploy key 不能跨仓复用，每仓得单独一把）
ssh -T git@github.com              # 成功显示：Hi <repo/user>! You've successfully authenticated

# === 拉代码 ===
sudo mkdir -p /opt/MogooUtils && sudo chown ubuntu:ubuntu /opt/MogooUtils
cd /opt && rm -rf MogooUtils       # 清掉可能残留的空目录
git clone git@github.com:silei10032/MogooUtils.git
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

> GitHub 访问已在 §2.2「一次性配置」搞定。若 §2.2 用的是**账号级 SSH key**，这里直接 clone；
> 若用的是 **per-repo deploy key**，得先给 jshERP 仓单独再加一把 deploy key（deploy key 不能跨仓复用）。

```bash
sudo mkdir -p /opt/mogooErp && sudo chown ubuntu:ubuntu /opt/mogooErp
cd /opt && rm -rf mogooErp
git clone git@github.com:silei10032/jshERP.git mogooErp
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
sudo chown -R ubuntu:ubuntu /data/mogoo-erp
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

## 阶段 4 · 域名 + HTTPS（host nginx 反代 + Let's Encrypt）

> 给已部署的应用配域名 + SSL。架构：
> `浏览器 ──https/443──▶ host nginx（域名 + 证书）──▶ 127.0.0.1:8082/8091（容器）`
> 每个应用一个子域名（`menu.` → MogooUtils、`erp.` → mogooErp），互不干扰。
>
> ⚠️ **大陆服务器前置条件：域名必须 ICP 备案**，否则腾讯云封 80 端口、域名打不开。
> 备案是按主体/根域名的，根域名备案后子域名（menu./erp./…）全覆盖，无需单独备案。

以 MogooUtils（`menu.mogoocloud.cn` → 容器 8082）为例，mogooErp 把 `menu`/`8082` 换成 `erp`/`8091` 再走一遍即可。
> ✅ mogooErp 已按此完成：`https://erp.mogoocloud.cn` → 127.0.0.1:8091，certbot 非交互签发（账号复用 menu 那次，命令 `sudo certbot --nginx -d erp.mogoocloud.cn -n --agree-tos --redirect`）。

### 4.1 DNS 解析

腾讯云 DNSPod → 域名 → 解析 → 添加记录：主机记录 `menu`、类型 `A`、记录值 `<公网IP>`、TTL 600。
本机 `dig +short menu.mogoocloud.cn` 返回公网 IP 即生效。

### 4.2 安全组放行 80/443

腾讯云安全组入站加两条：端口 `80`、`443`，来源 `0.0.0.0/0`（对公众开放的 web 端口，不走白名单）。

### 4.3 装 nginx + certbot，配反代

```bash
sudo apt install -y nginx certbot python3-certbot-nginx

sudo tee /etc/nginx/sites-available/menu.mogoocloud.cn >/dev/null <<'EOF'
server {
    listen 80;
    server_name menu.mogoocloud.cn;
    client_max_body_size 50m;          # 允许 Excel 上传（默认 1m 太小）

    location / {
        proxy_pass http://127.0.0.1:8082;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
EOF

sudo ln -sf /etc/nginx/sites-available/menu.mogoocloud.cn /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default       # 去掉默认欢迎页
sudo nginx -t && sudo systemctl reload nginx
# 此时 http://menu.mogoocloud.cn 应能打开登录页（纯 http）
```

### 4.4 签 SSL 证书

```bash
sudo certbot --nginx -d menu.mogoocloud.cn
# 交互：填邮箱 → Agree TOS=Y → share EFF=N → redirect HTTP→HTTPS 选 2
# certbot 自动：写入 443+证书、加 80→443 跳转、装自动续期定时器

# 验证
systemctl list-timers | grep certbot   # 见 certbot.timer = 自动续期已就绪
sudo certbot renew --dry-run            # 演练续期，success 即长期无忧
```

浏览器开 `https://menu.mogoocloud.cn`，带锁、登录页正常即完成。

### 4.5（可选）收紧：容器端口只绑本机

默认容器把 8082/8091 绑在 `0.0.0.0`，公网仍能 `http://IP:8082` 绕过 SSL 直连。要堵掉：
把对应 `docker-compose.yml` 的端口映射改成 `127.0.0.1:8082:80`、`docker compose up -d` 重建，
再到安全组**删掉 8082/8091**，只留 80/443。这样唯一公网入口就是带 SSL 的 nginx。

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
| `docker compose build` 拉 base 镜像 `registry-1.docker.io ... i/o timeout` | Docker Hub 被墙，配镜像加速器 `/etc/docker/daemon.json`（见 §1.2 步骤 4）后 `sudo systemctl restart docker` |
| `git clone` 报 `GnuTLS recv error -110` | github HTTPS 被 TLS 重置，改用 SSH over 443 + 密钥（见 §2.2 一次性配置） |
| `docker compose up` 后 app 容器 `Exit 1` | 看 `docker compose logs app`，绝大多数情况是 .env 或数据库未初始化 |

---

## 6. 推迟事项（不在本次部署范围）

到了需要时再做：

- ~~**域名 + Let's Encrypt SSL**~~ → ✅ 已完成，见 **阶段 4**（MogooUtils `https://menu.mogoocloud.cn`、mogooErp `https://erp.mogoocloud.cn` 均已上线，证书有效期至 2026-09-27，自动续期已配）
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
