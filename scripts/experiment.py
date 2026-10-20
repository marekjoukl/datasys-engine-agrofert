# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///

import argparse
import csv
import os
import platform
import random
import shutil
import statistics
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
EXPERIMENTS = REPO / "experiment"
ENGINE = REPO / "engine"

CITIES = ["Copenhagen", "Aarhus", "Odense", "Aalborg", "Esbjerg", "Roskilde"]
DEFAULT_SEED = 69
DEFAULT_SIZES = [8, 32, 128, 512, 1024, 2048, 8192, 32768]
ORDERS = ["sorted", "shuffled"]
LOG_COLUMNS = ("timestamp STRING, sessionId STRING, statementNumber LONG, threadId LONG, "
               "logLevel STRING, className STRING, logMessage STRING")


def main():
    parser = argparse.ArgumentParser(description="Partition-size sweep over sorted and shuffled data")
    commands = parser.add_subparsers(dest="command", required=True)

    run_parser = commands.add_parser("run", help="run the sweep into experiment/<label>")
    run_parser.add_argument("--rows", type=int, default=100_000)
    run_parser.add_argument("--sizes", type=int, nargs="+", default=DEFAULT_SIZES,
                            help="maxRowsPerPartition values")
    run_parser.add_argument("--orders", nargs="+", choices=ORDERS, default=ORDERS)
    run_parser.add_argument("--runs", type=int, default=6, help="SELECT runs per configuration, at least 2")
    run_parser.add_argument("--heap", default="1g", help="passed to the engine as -Xms and -Xmx")
    run_parser.add_argument("--label", help="directory name under experiment/, defaults to the short commit hash")
    run_parser.add_argument("--seed", type=int, default=DEFAULT_SEED)

    analyze_parser = commands.add_parser("analyze", help="summarize experiment/<label> into results.csv and runs.csv")
    analyze_parser.add_argument("label")

    generate_parser = commands.add_parser("generate", help="write a trips CSV to stdout: city,distance,price")
    generate_parser.add_argument("rows", type=int, nargs="?", default=5000)
    generate_parser.add_argument("order", choices=ORDERS, nargs="?", default="sorted")
    generate_parser.add_argument("--seed", type=int, default=DEFAULT_SEED)

    args = parser.parse_args()
    if args.command == "run":
        if args.runs < 2:
            parser.error("--runs must be at least 2: run 1 is reported apart from the rest")
        run(args)
    elif args.command == "analyze":
        analyze(args.label)
    else:
        sys.stdout.writelines(generate_trips(args.rows, args.order, args.seed))


def generate_trips(rows, order, seed):
    generator = random.Random(seed)
    lines = [f"{CITIES[int(generator.random() * len(CITIES))]},{distance},{distance * 1.5:.2f}\n"
             for distance in range(1, rows + 1)]
    if order == "shuffled":
        for i in range(len(lines) - 1, 0, -1):
            j = int(generator.random() * (i + 1))
            lines[i], lines[j] = lines[j], lines[i]
    return lines


def run(args):
    commit = git("rev-parse", "--short", "HEAD")
    out = EXPERIMENTS / (args.label or commit)
    if out.exists():
        sys.exit(f"{out} already exists: remove it or pass --label")
    if git("status", "--porcelain"):
        print(f"warning: uncommitted changes, the results will not match commit {commit}", file=sys.stderr)

    subprocess.run(["mvn", "-B", "-q", "-DskipTests", "package"], cwd=REPO, check=True)

    threshold = args.rows * 9 // 10
    expected = args.rows - threshold
    query = f"SELECT * FROM trips WHERE distance > {threshold}"

    out.mkdir(parents=True)
    write_conditions(out, args, query, expected)

    for order in args.orders:
        data = out / f"trips-{order}.csv"
        with data.open("w") as file:
            file.writelines(generate_trips(args.rows, order, args.seed))

        for size in args.sizes:
            directory = out / f"{order}-{size}"
            directory.mkdir()
            options = [f"-Xms{args.heap}", f"-Xmx{args.heap}", f"-Dengine.maxRowsPerPartition={size}"]

            engine(["-c", "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE)"], directory, options)
            engine(["-c", f"COPY trips FROM '../{data.name}'"], directory, options)

            for number in range(1, args.runs + 1):
                started = time.monotonic()
                result = directory / "result.csv"
                with result.open("w") as file:
                    engine(["-c", query], directory, options, stdout=file)
                with result.open() as file:
                    returned = sum(1 for _ in file)
                if returned != expected:
                    sys.exit(f"{order} size={size} run={number} returned {returned} rows, expected {expected}")
                print(f"{order} size={size} run={number}/{args.runs} rows={returned} "
                      f"wallSeconds={time.monotonic() - started:.1f}", flush=True)

    print(f"results in {out}")


def analyze(label):
    experiment = EXPERIMENTS / label
    if not (experiment / "conditions.txt").exists():
        sys.exit(f"{experiment} is not a finished sweep")

    analysis = experiment / "analysis"
    shutil.rmtree(analysis, ignore_errors=True)
    analysis.mkdir()

    configurations = []
    for log in sorted(experiment.glob("*/logs/engine.log")):
        name = log.parent.parent.name
        if name == "analysis":
            continue
        order, size = name.rsplit("-", 1)
        table = f"log_{order}_{size}"

        script = analysis / f"{name}.sql"
        script.write_text(f"CREATE TABLE {table} ({LOG_COLUMNS});\n"
                          f"COPY {table} FROM '../{name}/logs/engine.log';\n"
                          f"SELECT * FROM {table} WHERE className = 'Executor';\n")

        filtered = analysis / f"{name}.csv"
        with filtered.open("w") as file:
            engine(["-f", script.name], analysis, stdout=file)
        configurations.append(read_configuration(order, int(size), filtered))

    configurations.sort(key=lambda c: (c["order"], c["size"]))
    write_runs(experiment / "runs.csv", configurations)
    write_results(experiment / "results.csv", configurations)
    print(f"results in {experiment / 'results.csv'} and {experiment / 'runs.csv'}")


def read_configuration(order, size, filtered):
    copy_ms = None
    selects = []
    for line in filtered.read_text().splitlines():
        fields = message_fields(line.split(",", 6)[6])
        if fields.get("statement") == "COPY":
            copy_ms = int(fields["durationMs"])
        elif fields.get("statement") == "SELECT":
            selects.append(fields)

    if copy_ms is None or len(selects) < 2:
        sys.exit(f"{filtered} needs one COPY and at least two SELECT summary lines")
    for key in ["partitionsRead", "partitionsPruned", "rowsOut"]:
        if len({select[key] for select in selects}) != 1:
            sys.exit(f"{filtered}: {key} differs between runs")

    return {"order": order, "size": size, "copyMs": copy_ms, "selects": selects}


def message_fields(message):
    return dict(pair.split("=", 1) for pair in message.split(" ") if "=" in pair)


def write_runs(path, configurations):
    with path.open("w", newline="") as file:
        writer = csv.writer(file, lineterminator="\n")
        writer.writerow(["order", "maxRowsPerPartition", "run", "partitionsTotal", "partitionsRead",
                         "rowsOut", "queryMs", "durationMs"])
        for configuration in configurations:
            for number, select in enumerate(configuration["selects"], start=1):
                writer.writerow([configuration["order"], configuration["size"], number,
                                 partitions_total(select), select["partitionsRead"], select["rowsOut"],
                                 select["queryMs"], select["durationMs"]])


def write_results(path, configurations):
    with path.open("w", newline="") as file:
        writer = csv.writer(file, lineterminator="\n")
        writer.writerow(["order", "maxRowsPerPartition", "partitionsTotal", "partitionsRead", "fractionRead",
                         "rowsOut", "copyMs", "runs",
                         "firstQueryMs", "medianQueryMs", "minQueryMs", "maxQueryMs",
                         "firstDurationMs", "medianDurationMs", "minDurationMs", "maxDurationMs"])
        for configuration in configurations:
            first, rest = configuration["selects"][0], configuration["selects"][1:]
            total = partitions_total(first)
            read = int(first["partitionsRead"])
            writer.writerow([configuration["order"], configuration["size"], total, read, f"{read / total:.4f}",
                             first["rowsOut"], configuration["copyMs"], len(configuration["selects"]),
                             *statistics_of(first, rest, "queryMs"), *statistics_of(first, rest, "durationMs")])


def statistics_of(first, rest, key):
    values = [int(select[key]) for select in rest]
    return [first[key], statistics.median(values), min(values), max(values)]


def partitions_total(select):
    return int(select["partitionsRead"]) + int(select["partitionsPruned"])


def engine(args, cwd, options=None, stdout=subprocess.DEVNULL):
    environment = {**os.environ, "ENGINE_JAVA_OPTS": " ".join(options or [])}
    completed = subprocess.run([str(ENGINE), *args], cwd=cwd, env=environment,
                               stdout=stdout, stderr=subprocess.DEVNULL)
    if completed.returncode != 0:
        sys.exit(f"engine failed on {' '.join(args)} (see {cwd / 'logs' / 'engine.log'})")


def write_conditions(out, args, query, expected):
    conditions = {
        "date": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "commit": git("rev-parse", "HEAD"),
        "uncommittedChanges": "yes" if git("status", "--porcelain") else "no",
        "os": f"{platform.system()} {platform.release()} {platform.machine()}",
        "cpu": cpu(),
        "memoryBytes": memory(),
        "java": java_version(),
        "heap": args.heap,
        "rows": args.rows,
        "seed": args.seed,
        "orders": " ".join(args.orders),
        "sizes": " ".join(str(size) for size in args.sizes),
        "runs": args.runs,
        "query": query,
        "expectedRows": expected,
    }
    (out / "conditions.txt").write_text("".join(f"{key}={value}\n" for key, value in conditions.items()))


def cpu():
    if platform.system() == "Darwin":
        return sysctl("machdep.cpu.brand_string")
    for line in Path("/proc/cpuinfo").read_text().splitlines():
        if line.startswith("model name"):
            return line.split(":", 1)[1].strip()
    return platform.processor()


def memory():
    if platform.system() == "Darwin":
        return sysctl("hw.memsize")
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("MemTotal"):
            return int(line.split()[1]) * 1024
    return "unknown"


def sysctl(name):
    return subprocess.run(["sysctl", "-n", name], capture_output=True, text=True).stdout.strip()


def java_version():
    completed = subprocess.run(["java", "-version"], capture_output=True, text=True)
    return completed.stderr.splitlines()[0]


def git(*args):
    return subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True,
                          check=True).stdout.strip()


if __name__ == "__main__":
    main()
