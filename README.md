# SODAviewer

Portable **SODA archive viewer** (Java / Swing). Open a `.soda` folder and plot **UMAP / PCA** colored by gene expression — IGV-style bundled runtime, no install wizard.

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

## Build locally

```bash
./build.sh          # fat JAR (this machine)
./package-app.sh    # bundled app for *this* OS only → dist/
```

On a Mac you only get a macOS `.app`. For Windows/Linux zips, push a tag or trigger CI — or run `./package-app.sh` on those machines.

Dev run without packaging:

```bash
./run.sh /path/to/PBMC.soda
```

## Use

1. Launch → branded **welcome** (drop a `.soda` or Open)
2. **Overview** — interactive archive `index.html`
3. Reduction → **Choose…** then **pca / umap / cgumap** (switches to plot)
4. **Plot clusters** / **Plot gene** / **Gene box**
5. Gene box: one gene = box; `gene1, gene2, …` = bubble heatmap
6. **File → Export plot…** → PNG / JPG / PDF

Menus: **File · Edit · View · Help**

Large objects: randomly shows up to **100,000** cells (fixed seed) for speed.

## Version

| Field | Value |
|-------|-------|
| SODAviewer | **0.2.0** (`VERSION`, `pom.xml`, `AppVersion.VERSION`, CLI `--version`) |
| Compatible SODA | **soda_version 1.0.0** / **schema_version 1.0.0** |
| Producer tips | iCellR2 **2.0.5**, iCellPy **0.2.x** |

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
