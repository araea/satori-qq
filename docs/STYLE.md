# 代码规范

知弦、知言（satori-wx）与 acumen 三个仓库共用同一套底线，再按语言落到各自的工具上。

## 通用

- `.editorconfig`：UTF-8、LF、文件末尾换行、去行尾空白、4 空格（Markdown、JSON、CSS 为 2 空格）。三个仓库同一份。
- 提交信息写成 `类型(范围): 一句话`，类型取 feat / fix / refactor / style / docs / test / chore / release，正文讲为什么。
- 不留死代码：不参与构建的文件、没人调用的方法直接删，历史在 git 里。
- Shell 脚本里写 `CDPATH='' cd -- …`，不写 `CDPATH= cd`；脚本用 `shellcheck` 过一遍。

## Java（`src/`、`tests/`）

- 语言级别 Java 17：`javac --release 17`，D8 脱糖到 min-api 26。record、switch 表达式、`instanceof` 模式匹配可用，
  `switch` 上的模式匹配（Java 21）不用。
- 只用 Android 26 有的类库，或 D8 能回填的方法（`List.of`、`Map.of`、`String.isBlank` 一类）。`javac` 对着 JDK 的类库编译而不是
  `android.jar`，`Files.readString`、`URLDecoder.decode(String, Charset)` 这种较新的 API 编译期查不出来，上真机才 `NoSuchMethodError`。
- import 排成一块：静态在前，其余按 ASCII 序；不写行内全限定名。`scripts/java-imports.py --check` 检查，`test.sh` 会跑它。
- HTTP 层只抛 `ApiError`（`badRequest` / `notFound` / `failed` / …），不直接 new 状态码。
- 线程名外部可见（`/proc/<pid>/task/comm`），一律经 `Workers`，按 JVM 线程池的默认风格命名，不带模块名。
- `core` 的部件互相只经构造参数依赖，`SatoriHub` 只接线；换号要清的缓存各部件自己实现 `reset()`。

## C++（`native/satori.cpp`）

`.clang-format` 与 satori-wx 同一份：`clang-format -i native/satori.cpp`。`zygisk.hpp` 来自 Zygisk Next，不改。
