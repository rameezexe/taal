#!/usr/bin/env python3
"""
Builds a tester release and the download site in site/public:
  - signed release APK  → site/public/download/taal-<version>.apk
  - version.json        → read by the app's update check (UPDATE_URL = <SITE_URL>/version.json)
  - index.html          → download page (from site/index.template.html)
  - privacy.html        → from docs/privacy-policy.md
  - assets/             → from site/assets (logo, icons, link-preview image)

Needs SITE_URL in local.properties (https://taal.taal-app.workers.dev). Notes for the update card: --notes "...".
Deploy afterwards:  npx wrangler deploy --config site/wrangler.toml
Usage:  scripts/release.py [--notes "What changed"] [--no-build]
"""
import argparse, datetime, hashlib, html, json, pathlib, re, shutil, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "site" / "public"


def props():
    p = {}
    for line in (ROOT / "local.properties").read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            p[k.strip()] = v.strip()
    return p


def version():
    gradle = (ROOT / "app" / "build.gradle.kts").read_text()
    code = int(re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1))
    name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
    return code, name


def inline(text):
    text = html.escape(text, quote=False)
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"_(.+?)_", r"<em>\1</em>", text)
    return re.sub(r"\[([^\]]+)\]\((https?://[^)]+)\)", r'<a href="\2">\1</a>', text)


def markdown(md):
    """Just enough Markdown for the privacy policy: headings, paragraphs, lists, tables."""
    out, para, items, table = [], [], [], []

    def flush():
        if para:
            out.append("<p>" + inline(" ".join(para)) + "</p>"); para.clear()
        if items:
            out.append("<ul>" + "".join("<li>" + inline(i) + "</li>" for i in items) + "</ul>"); items.clear()
        if table:
            rows = [r for r in table if not re.fullmatch(r"\|[\s|:-]+\|", r)]
            cells = [[c.strip() for c in r.strip("|").split("|")] for r in rows]
            head = "".join("<th>" + inline(c) + "</th>" for c in cells[0])
            body = "".join("<tr>" + "".join("<td>" + inline(c) + "</td>" for c in r) + "</tr>" for r in cells[1:])
            out.append(f"<table><tr>{head}</tr>{body}</table>"); table.clear()

    for line in md.splitlines():
        if m := re.match(r"(#{1,3}) (.*)", line):
            flush(); n = len(m.group(1)); out.append(f"<h{n}>{inline(m.group(2))}</h{n}>")
        elif line.startswith("|"):
            if para or items: flush()
            table.append(line)
        elif m := re.match(r"- (.*)", line):
            if para or table: flush()
            items.append(m.group(1))
        elif line.startswith("  ") and items:
            items[-1] += " " + line.strip()
        elif not line.strip():
            flush()
        else:
            if items or table: flush()
            para.append(line.strip())
    flush()
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--notes", default="", help="shown in the app's Update available card")
    ap.add_argument("--no-build", action="store_true")
    args = ap.parse_args()

    site = props().get("SITE_URL", "").rstrip("/")
    if not site.startswith("https://"):
        sys.exit("Set SITE_URL=https://… in local.properties (and UPDATE_URL=<SITE_URL>/version.json for the app).")
    if props().get("UPDATE_URL") != f"{site}/version.json":
        print(f"warning: UPDATE_URL in local.properties should be {site}/version.json, or the app won't see updates")

    if not args.no_build:
        subprocess.run(["./gradlew", ":core:test", ":app:lintRelease", ":app:assembleRelease"], cwd=ROOT, check=True)
    apk = ROOT / "app/build/outputs/apk/release/app-release.apk"
    signer = subprocess.run(
        [str(sorted((pathlib.Path.home() / "Library/Android/sdk/build-tools").iterdir())[-1] / "apksigner"),
         "verify", "--print-certs", str(apk)], capture_output=True, text=True,
    ).stdout
    if "ff70da48038543a62498f473188e46be4f3a31c1" not in signer:
        sys.exit("The APK isn't signed with the Taal release key: testers couldn't update. Check RELEASE_* in local.properties.")

    code, name = version()
    shutil.rmtree(OUT, ignore_errors=True)
    (OUT / "download").mkdir(parents=True)
    apk_name = f"taal-{name}.apk"
    shutil.copy(apk, OUT / "download" / apk_name)
    data = (OUT / "download" / apk_name).read_bytes()
    sha = hashlib.sha256(data).hexdigest()

    shutil.copytree(ROOT / "site/assets", OUT / "assets")  # logo, icons, link-preview image

    (OUT / "version.json").write_text(json.dumps({
        "versionCode": code, "versionName": name, "url": f"{site}/download/{apk_name}", "notes": args.notes,
    }, indent=2))
    page = (ROOT / "site/index.template.html").read_text()
    for k, v in {
        "version": name, "apk": f"download/{apk_name}", "sha256": sha, "site": site,
        "size": f"{len(data) / 1_048_576:.1f} MB", "date": datetime.date.today().strftime("%d %b %Y"),
    }.items():
        page = page.replace("{{" + k + "}}", v)
    (OUT / "index.html").write_text(page)
    style = re.search(r"<style>.*?</style>", page, re.S).group(0) + \
        "<style>table{border-collapse:collapse;width:100%;font-size:14px}td,th{border:1px solid var(--hair);padding:6px;text-align:left;vertical-align:top}</style>"
    (OUT / "privacy.html").write_text(
        '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">'
        f"<title>Taal · privacy policy</title>{style}</head><body><main>"
        + markdown((ROOT / "docs/privacy-policy.md").read_text()) + '<footer><a href="index.html">Taal</a> · Made by <a href="https://portfolio-beige-one-acuumxf4x9.vercel.app/">Rameez</a></footer></main></body></html>'
    )
    # Pages serves version.json fresh (the app checks it twice a day) and the APK with the right type.
    (OUT / "_headers").write_text(
        "/version.json\n  Cache-Control: no-cache\n/download/*\n  Content-Type: application/vnd.android.package-archive\n"
    )
    print(f"Taal {name} ({code}) → {OUT}\n  APK {len(data) / 1_048_576:.1f} MB, sha256 {sha}")
    print("Deploy:  npx wrangler deploy --config site/wrangler.toml")


if __name__ == "__main__":
    main()
