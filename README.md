# SODAviewer

Portable **SODA archive viewer** (Java / Swing). Open a `.soda` folder and plot **PCA / UMAP / KNetL** (plus aligned_* and cgumap/cgpca when present) colored by gene expression — IGV-style bundled runtime, no install wizard.

## Install (end users)

Download the zip for **your OS** from [Releases](../../releases), unzip, run:

| Platform | Download | How to run |
|----------|----------|------------|
| macOS Apple Silicon | `SODAviewer-*-macos-arm64.dmg` or `.zip` | DMG: drag to Applications · Zip: open `SODAviewer.app` |
| macOS Intel | `SODAviewer-*-macos-x64.dmg` or `.zip` | DMG: drag to Applications · Zip: open `SODAviewer.app` |
| Windows | `SODAviewer-*-windows-x64.zip` | Run `SODAviewer/SODAviewer.exe` |
| Linux | `SODAviewer-*-linux-x64.zip` | Run `SODAviewer/bin/SODAviewer` |
| Any OS (needs Java 17+) | `SODAviewer-*.jar` | `java -jar SODAviewer-*.jar` |

No Java install for the platform zips (JRE is inside).

**macOS Gatekeeper:** first launch → right-click → **Open** (not notarized yet).

Bundles are built **per OS** (jpackage cannot cross-compile). GitHub Actions builds Mac / Windows / Linux when you push a `v*` tag or run the **Release bundles** workflow.

### Windows `.exe` (GitHub Actions)

Native `SODAviewer.exe` (jpackage on `windows-2022`):

1. Push this repo to GitHub (add a remote if you do not have one yet).
2. Open **Actions** → **Windows dist (.exe)** → **Run workflow** (manual), or push to `main`/`master` after changing packaging files.
3. When the run finishes → open the run → **Artifacts** → download `SODAviewer-windows-x64-*` (contains `SODAviewer.exe` and the windows-x64 zip).

Workflow file: [`.github/workflows/windows-dist.yml`](.github/workflows/windows-dist.yml).

## Build locally

```bash
./build.sh                      # fat JAR (this machine)
./package-app.sh                # jpackage app-image for *this* OS only → dist/
./package-windows-portable.sh   # Windows x64 portable zip from any host → dist/
```

`jpackage` cannot cross-compile. On a Mac, `./package-app.sh` yields a macOS `.app`. For a **native Windows `.exe`** app-image, run `./package-app.sh` on Windows, or use **Actions → Windows dist (.exe)** above (or the multi-OS **Release bundles** workflow).

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
