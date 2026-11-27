# SODAviewer

Portable **SODA archive viewer** (Java / Swing). Open a `.soda` folder and plot **PCA / UMAP / KNetL** (plus aligned_* and cgumap/cgpca when present) colored by gene expression — IGV-style bundled runtime, no install wizard.

## Downloads (v0.2.4)

Direct links from the [v0.2.4 release](https://github.com/rezakj/SODAviewer/releases/tag/v0.2.4):

| Platform | Asset | Link |
|----------|-------|------|
| macOS (Apple Silicon) | `SODAviewer-0.2.4-macos-arm64.dmg` | [Download](https://github.com/rezakj/SODAviewer/releases/download/v0.2.4/SODAviewer-0.2.4-macos-arm64.dmg) |
| macOS (Apple Silicon) | `SODAviewer-0.2.4-macos-arm64.zip` | [Download](https://github.com/rezakj/SODAviewer/releases/download/v0.2.4/SODAviewer-0.2.4-macos-arm64.zip) |
| Windows (portable) | `SODAviewer-windows.zip` | [Download](https://github.com/rezakj/SODAviewer/releases/download/v0.2.4/SODAviewer-windows.zip) |
| Linux / any OS (JAR) | `SODAviewer-0.2.4.jar` | [Download](https://github.com/rezakj/SODAviewer/releases/download/v0.2.4/SODAviewer-0.2.4.jar) |

- **macOS:** DMG → drag to Applications · Zip → open `SODAviewer.app`. First launch: right-click → **Open** (not notarized yet).
- **Windows:** Unzip → double-click `SODAviewer.bat` (bundled JRE). A native `SODAviewer.exe` may appear later via **Actions → Windows dist (.exe)**.
- **Linux / JAR:** `java -jar SODAviewer-0.2.4.jar` (needs Java 17+).

No Java install for the macOS/Windows platform bundles (JRE is inside).

Older or other platforms: see all [Releases](../../releases).

## Install (end users)

Bundles are built **per OS** (jpackage cannot cross-compile). GitHub Actions builds Mac / Windows / Linux and publishes them to a [GitHub Release](../../releases) when you push a `v*` tag (e.g. `v0.2.4`) or run **Release bundles** with a release tag.

```bash
# From a commit that has VERSION=0.2.4
git tag v0.2.4
git push origin v0.2.4
# → Actions "Release bundles" builds Mac dmg/zip, Windows portable zip, Linux zip
```

Workflow: [`.github/workflows/release.yml`](.github/workflows/release.yml).

### Windows `.exe` (optional, GitHub Actions)

Native `SODAviewer.exe` via jpackage on `windows-2022` (**PowerShell only** — no Git Bash):

1. Open **Actions** → **Windows dist (.exe)** → **Run workflow**.
2. Optional: enable **Publish to GitHub Release** and set tag `v0.2.4` to attach the exe/zip to that Release.
3. Or leave publish off and download from the run **Artifacts** → `SODAviewer-windows-x64-exe-*`.

Until that job succeeds, use the portable zip (`.bat`) above.

Workflow: [`.github/workflows/windows-dist.yml`](.github/workflows/windows-dist.yml).

## Build locally

```bash
./build.sh                      # fat JAR (this machine)
./package-app.sh                # jpackage app-image for *this* OS only → dist/
./package-windows-portable.sh   # Windows x64 portable zip from any host → dist/
# On Windows (PowerShell), native exe:
#   mvn -B -DskipTests package
#   # put javafx-*-win.jar in lib\javafx\
#   .\package-windows-exe.ps1
```

`jpackage` cannot cross-compile. On a Mac, `./package-app.sh` yields a macOS `.app` / `.dmg`. For a **native Windows `.exe`**, run `.\package-windows-exe.ps1` on Windows, or **Actions → Windows dist (.exe)**.

From macOS/Linux you can still ship a usable Windows bundle with `./package-windows-portable.sh` → `dist/SODAviewer-*-windows-x64.zip` (bundled Temurin JRE + Windows OpenJFX + `SODAviewer.bat`). See `dist/WINDOWS-BUILD.txt`.

Dev run without packaging:

```bash
./run.sh /path/to/PBMC.soda
```

## Use

1. Launch → branded **welcome** (drop a `.soda` or Open)
2. **Overview** — interactive archive `index.html`
3. Reduction → **Choose…** then **pca / umap / knetl** (aligned_* / cgumap / cgpca when present; switches to plot)
4. **Plot clusters** / **Plot gene** / **Gene box**
5. Gene box: one gene = box; `gene1, gene2, …` = bubble heatmap
6. **File → Export plot…** → PNG / JPG / PDF
7. **Lasso/cluster tools** assign/merge/rename · **Export to SODA** writes `cluster_id` (confirm warning)

Menus: **File · Edit · View · Help**

Large objects: randomly shows up to **100,000** cells (fixed seed) for speed.

## Version

| Field | Value |
|-------|-------|
| SODAviewer | **0.2.4** (`VERSION`, `pom.xml`, `AppVersion.VERSION`, CLI `--version`) |
| Compatible SODA | **soda_version 1.0.0** / **schema_version 1.0.0** |
| Producer tips | iCellR2 **2.0.11**, iCellPy **0.2.9** |

```bash
java -jar target/SODAviewer.jar --version
```

## Layout expected

```
*.soda/
  soda.json                    # soda_version + schema_version (+ package_version)
  reductions/<name>/cell_embeddings.h5
  # and/or modalities/<mod>/reductions/<name>/cell_embeddings.h5
  modalities/rna/assays/data/assay_source.h5   # or counts/<sample>/*.h5
  metadata/cell_data.tsv
  metadata/version.json
```

Plottable reductions include `pca`, `umap`, `cgumap`, `cgpca`, `knetl`, and `aligned_*`.
Reads both iCellR2 CSC (`i/p/x`) and Python/legacy sparse layouts, plus 10x `/matrix` counts if needed.

## License

SODAviewer is released under the **GNU General Public License v3.0** (GPL-3.0). See [`LICENSE`](LICENSE).
