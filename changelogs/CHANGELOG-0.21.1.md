## 0.21.1 · 知弦

### 自隐加自检

0.21.0 换掉了整套旧反检测，只留「模块自隐」。这一版把自隐的验证也做进 `/healthz`，不再靠推测：

- `shield.pm`：在 QQ 进程里直接查一次自己的包名。
  `visible=false` 表示 `getPackageInfo("com.satori.qq")` 抛 `NameNotFoundException`，
  `listed=false` 表示 `getInstalledPackages` 的结果里也没有它。
- `shield.hits`：按检测库的思路读 `/proc/self/{maps,smaps,mounts,mountinfo,cmdline,status,environ}`，
  统计黑名单字面量命中数（lsposed / zygisk / magisk / libriru / me.bmax.apatch / kernelsu /
  apatch / susfs）。真机实测为 0。

其它不变：不加载 native 库、不写任何文件、不碰认证与人脸链路、不拦上报。
