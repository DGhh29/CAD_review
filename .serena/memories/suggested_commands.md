# CAD_review 常用命令

- `git status --short`：查看工作区改动。
- `git diff --check`：检查空白和补丁格式问题。
- `rg -n "pattern" src/main/java src/test/java`：快速定位代码。
- `Get-Content -Raw -Encoding UTF8 <file>`：在 Windows PowerShell 下读取文件。
- Maven 构建/测试命令在当前环境不可直接执行，因为 shell 里没有 `mvn` 或 `mvnw`；需要可用的 Maven 安装后再跑 `mvn -DskipTests compile` 或 `mvn test`。