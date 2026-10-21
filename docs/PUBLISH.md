# 上传到 GitHub 的步骤

**当前状态（2026-10-22）**：仓库已推送到 <https://github.com/CielHou/soundcore-Remote>，
首个 Release `v1.0.0` 已发布，两个安装包已作为附件上传：

- `audcore-remote-1.0.0-debug.apk`（9.9 MB）
- `audcore-gateway-setup-1.0.0.exe`（36.2 MB）

下面保留完整流程，用于**以后发新版本**时照做。

## 0. 上传前必做：换掉令牌（安全）

`gateway/.state/client-token.txt` 里的手机令牌曾经出现在文档示例里，现在文档已改成
`<手机令牌>` 占位符，但**建议轮换一次**：

1. 删除或改写 `gateway/.state/client-token.txt`（至少 8 个字符）；
2. 重启网关，重新在窗口点「展示二维码」；
3. 手机扫新二维码（旧地址会 401）。

声核授权凭证 `gateway/.state/audcore-token.txt` 从未离开本机，不必改。

## 1. 在 GitHub 建空仓库

网页：<https://github.com/new>

- Repository name：`soundcore-Remote`（可自定）
- **不要**勾选 “Add a README file / .gitignore / license”，仓库要是空的
- 公开或私有都行（私有仓库的 Actions 会消耗额度）

> 仓库名不是 `soundcore-Remote` 时，README / gateway README 里的 `../../releases/latest`
> 是相对链接，无需修改。

## 2. 关联远程仓库并推送

如果你的 GitHub 用户名不是 `CielHou`，把下面 URL 里的用户名换掉：

```powershell
cd <仓库目录>
git remote add origin https://github.com/CielHou/soundcore-Remote.git
git push -u origin main
```

首次推送会弹浏览器授权（Git Credential Manager），或使用 Personal Access Token 作为密码。

## 3. 发 Release（安装包不放仓库，放这里）

网页：仓库 → Releases → Draft a new release

| 项 | 值 |
| --- | --- |
| Tag | `v1.0.0`（新建标签，目标 `main`） |
| Title | `声核遥控 1.0.0` |
| 附件 | `audcore-remote-1.0.0-debug.apk`（本地 `声核遥控-debug.apk`）、`audcore-gateway-setup-1.0.0.exe`（本地 `安装包/声核网关安装程序.exe`） |

> 附件名用 ASCII：中文名上传后会被 GitHub 改坏（实测变成 `-debug.apk` / `default.exe`）。

Release 说明可以直接粘贴 `CHANGELOG.md` 里 `[1.0.0]` 那一节。

命令行等价操作（需要 [gh CLI](https://cli.github.com/)）：

```powershell
gh release create v1.0.0 `
  "audcore-remote-1.0.0-debug.apk" `
  "audcore-gateway-setup-1.0.0.exe" `
  --title "声核遥控 1.0.0" --notes-file CHANGELOG.md
```

> 没有 `gh` 时也可以用 GitHub REST API 上传（`POST https://uploads.github.com/repos/<owner>/<repo>/releases/<id>/assets?name=<文件名>`，body 直接放二进制）。

## 4. 上传后核对

- [x] 仓库页能正常渲染 `README.md`（截图能显示，许可证徽章是 MIT）
- [x] `git ls-files` 里没有 `.state` / `.apk` / `.exe`（密钥与产物没进库）
- [x] 「Insights → Languages」里主要是 Kotlin / JavaScript / C#
- [ ] Actions 里 `CI` 两个 job 通过（网关测试 + Android 构建）——首次推送后到 Actions 页确认
- [x] Releases 页面两个安装包可下载，页面上显示的 sha256 与本地一致
      （apk `b61e95c9…`、exe `968be8fe…`）

## 5. CI 徽章

已加在 `README.md` 顶部（用户名不同就替换 `CielHou`）：

```markdown
[![CI](https://github.com/CielHou/soundcore-Remote/actions/workflows/ci.yml/badge.svg)](https://github.com/CielHou/soundcore-Remote/actions/workflows/ci.yml)
```

## 6. 后续版本发布流程

```powershell
# 1) 改版本号
#    app/build.gradle.kts        versionCode / versionName
#    gateway/build/.installer-version
#    gateway/desktop/GatewaySetup/Program.cs  AppVersion
# 2) 构建
.\gradlew.bat :app:assembleDebug
pwsh -File gateway\build\build.ps1
pwsh -File gateway\build\build-setup.ps1
# 3) 记录
#    更新 CHANGELOG.md 与 README 的版本说明
# 4) 提交并打标签
git add -A; git commit -m "release: 1.0.1"
git tag v1.0.1; git push origin main --tags
# 5) 用 gh release create 或网页上传两个安装包
```
