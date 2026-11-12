#!/usr/bin/env python3
"""Consulta o OSV (https://osv.dev) para as dependências Maven resolvidas e falha se houver vulnerabilidade conhecida
de gravidade HIGH ou CRITICAL.

Uso:
    mvn -B -ntp -q dependency:list -DincludeScope=runtime -DoutputFile=target/deps.txt
    python3 scripts/ci/osv_check.py target/deps.txt

Só dependências de runtime/compile entram (o que vai para a imagem). A gravidade vem do registro do OSV
(`database_specific.severity`, presente nos avisos do GitHub); vulnerabilidade sem gravidade informada é listada como
UNKNOWN e NÃO derruba o build, mas aparece na saída para revisão. Variável OSV_API só existe para testar com um servidor
falso; o padrão é a API pública.

Saída: 0 sem achados HIGH/CRITICAL · 1 com achados · 2 erro de uso/rede (não confundir "não consegui consultar" com "limpo").
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

API = os.environ.get("OSV_API", "https://api.osv.dev").rstrip("/")
FAILING = {"HIGH", "CRITICAL"}
LINE = re.compile(r"^\s+([^:\s]+):([^:\s]+):[^:\s]+:(?:[^:\s]+:)?([^:\s]+):(compile|runtime)\b")


def request(path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)


def parse(path):
    packages = []
    for line in open(path, encoding="utf-8"):
        match = LINE.match(line)
        if match:
            group, artifact, version, _scope = match.groups()
            packages.append((f"{group}:{artifact}", version))
    return sorted(set(packages))


def severity_of(vuln):
    declared = str((vuln.get("database_specific") or {}).get("severity", "")).upper()
    return "HIGH" if declared == "IMPORTANT" else declared or "UNKNOWN"


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    packages = parse(argv[1])
    if not packages:
        print(f"nenhuma dependência encontrada em {argv[1]}: o arquivo é a saída de dependency:list?", file=sys.stderr)
        return 2
    print(f"consultando o OSV para {len(packages)} dependências Maven...")
    found = {}  # id -> [pacotes]
    try:
        for start in range(0, len(packages), 500):
            chunk = packages[start:start + 500]
            queries = [{"package": {"name": name, "ecosystem": "Maven"}, "version": version} for name, version in chunk]
            results = request("/v1/querybatch", {"queries": queries}).get("results", [])
            if len(results) != len(chunk):
                print("resposta do OSV com tamanho inesperado", file=sys.stderr)
                return 2
            for (name, version), result in zip(chunk, results):
                for vuln in result.get("vulns", []):
                    found.setdefault(vuln["id"], []).append(f"{name}@{version}")
        details = {vuln_id: request(f"/v1/vulns/{vuln_id}") for vuln_id in found}
    except (urllib.error.URLError, OSError, ValueError, KeyError) as error:
        print(f"não consegui consultar o OSV: {error}", file=sys.stderr)
        return 2

    failing = []
    for vuln_id, where in sorted(found.items()):
        vuln = details[vuln_id]
        if vuln.get("withdrawn"):
            continue
        severity = severity_of(vuln)
        summary = (vuln.get("summary") or "").strip()[:100]
        print(f"  [{severity:<8}] {vuln_id}  {', '.join(sorted(set(where)))}  {summary}")
        if severity in FAILING:
            failing.append(vuln_id)
    print()
    if failing:
        print(f"FALHA: {len(failing)} vulnerabilidade(s) HIGH/CRITICAL: {', '.join(failing)}")
        return 1
    print("OK: nenhuma vulnerabilidade HIGH/CRITICAL conhecida nas dependências Maven de runtime.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
