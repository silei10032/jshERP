# ---- Build Stage ----
# 锁 node:16 是因为 jshERP 前端依赖 vue-cli-service 3.x / webpack 4 等老栈，
# node 18+ 会触发 OpenSSL legacy provider 报错（ERR_OSSL_EVP_UNSUPPORTED）
FROM node:16-alpine AS build
WORKDIR /app
# 走 npmmirror（国内镜像）+ 关掉 strict-ssl，规避公司代理/加速器的 TLS 中间人拦截
RUN npm config set registry https://registry.npmmirror.com \
    && npm config set strict-ssl false \
    && npm config set fetch-timeout 600000 \
    && npm config set fetch-retries 5
COPY package*.json ./
# package-lock.json 可能不存在（项目用 yarn 或未提交 lock），降级为 npm install。
# --legacy-peer-deps：项目是 Vue CLI 3 + webpack 4 老栈，npm 7+ 严格 peer dep 解析会和
# vue-loader 15.x 间接拉的 css-loader 7（要求 webpack 5）冲突，必须降级解析模式
RUN npm install --no-audit --no-fund --legacy-peer-deps
COPY . .
RUN npm run build

# ---- Runtime Stage ----
FROM nginx:1.27
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
