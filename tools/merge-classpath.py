#!/usr/bin/env python3
"""
把 Paper 服务端 jar 和它 libraries/ 下的依赖合并成一个编译用 classpath jar。

Paper 从 1.20.5 起运行时使用 Mojang 映射，因此 NMS 插件不再需要 BuildTools +
specialsource 重映射，直接对着服务端 jar 编译即可。

用法:
  python merge-classpath.py \
      --server versions/26.3/paper-26.3.jar \
      --libraries libraries \
      --out lib/paper-26.3-compile.jar

注意: 产物包含 Mojang 版权代码，仅限本地编译使用，不要随插件分发。
"""

import argparse
import glob
import os
import zipfile


def add_all(target: zipfile.ZipFile, path: str, seen: set[str]) -> int:
    added = 0
    with zipfile.ZipFile(path) as source:
        for info in source.infolist():
            if info.is_dir():
                continue
            name = info.filename
            # 丢掉签名/元数据，避免合并后出现无效签名
            if name.startswith("META-INF/"):
                continue
            if name in seen:
                continue
            seen.add(name)
            target.writestr(name, source.read(name))
            added += 1
    return added


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", required=True, help="versions/<ver>/paper-<ver>.jar")
    parser.add_argument("--libraries", required=True, help="服务端目录下的 libraries/")
    parser.add_argument("--out", required=True, help="输出 jar 路径")
    args = parser.parse_args()

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)

    seen: set[str] = set()
    total = 0
    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as out:
        total += add_all(out, args.server, seen)

        jars = glob.glob(os.path.join(args.libraries, "**", "*.jar"), recursive=True)
        for jar in sorted(jars):
            try:
                total += add_all(out, jar, seen)
            except zipfile.BadZipFile:
                print(f"skip broken jar: {jar}")

    size = os.path.getsize(args.out)
    print(f"entries={total} size={size / 1024 / 1024:.1f} MB -> {args.out}")


if __name__ == "__main__":
    main()
