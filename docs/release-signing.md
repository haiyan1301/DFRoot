# Release 签名配置

原仓库通过 GitHub Actions Secrets 保存正式签名，`keystore.jks` 和
`signing.properties` 不在公开源码中。可以沿用其 JKS/RSA-2048 配置，但每个
fork 需要自己的固定私钥，不能仅复制 Makefile 中的别名或密码来获得原作者签名。

在仓库 **Settings → Secrets and variables → Actions** 配置以下 Repository Secrets：

| 名称 | 内容 |
| --- | --- |
| `KEYSTORE` | JKS 密钥库文件内容的 Base64 编码 |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 密钥库中的签名密钥别名 |
| `KEY_PASSWORD` | 该签名密钥的密码 |

如果使用提供的私有签名配置包，在本机解压后，以具有该仓库 Secrets 读写权限的
账号登录 GitHub CLI，再在解压目录运行 `python3 configure-github-secrets.py`。
该脚本上传包内已有的固定密钥，默认拒绝覆盖已有签名 Secret。
配置完成后，从 Actions 运行 `Build` 工作流即可验证 Release APK 构建。

请私下备份密钥库、密码和别名，并让后续版本持续使用同一把密钥，以便覆盖升级。
不要将这些文件或值提交到公开仓库。首次由原版、Debug 版或其他签名切换到
fork 的 Release APK 时，需要卸载签名不同的旧包后再安装；卸载会清除应用数据
和设置，应先保存需要的配置。
