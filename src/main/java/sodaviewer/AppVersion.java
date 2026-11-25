package sodaviewer;

/**
 * SODAviewer package version and compatible SODA on-disk layout.
 * Keep in sync with {@code VERSION}, {@code pom.xml}, and {@code package-app.sh}.
 */
public final class AppVersion {
    /** Application / package version (semver). */
    public static final String VERSION = "0.2.2";

    /**
     * Compatible SODA format version ({@code soda.json} / {@code soda.info}
     * {@code soda_version}). Written by iCellR2 {@code make.obj} and iCellPy {@code make_obj}.
     */
    public static final String COMPATIBLE_SODA_VERSION = "1.0.0";

    /**
     * Compatible schema version ({@code schema_version} in {@code soda.json} /
     * {@code metadata/version.json}).
     */
    public static final String COMPATIBLE_SCHEMA_VERSION = "1.0.0";

    /** Documented producer tips this build targets. */
    public static final String COMPATIBLE_ICELLR2 = "2.0.5";
    public static final String COMPATIBLE_ICELLPY = "0.2.x";

    private AppVersion() {}

    public static String aboutText() {
        return """
                SODAviewer %s
                Portable SODA archive viewer.

                Compatible SODA layout: soda_version %s / schema %s
                Targets: iCellR2 %s · iCellPy %s

                • Drop or open a .soda folder
                • Overview shows the archive index.html (when present)
                • Color by clusters / conditions / pseudotime; plot genes
                • Lasso assign / merge / rename · Export to SODA (writes cell_data)
                """.formatted(
                VERSION,
                COMPATIBLE_SODA_VERSION,
                COMPATIBLE_SCHEMA_VERSION,
                COMPATIBLE_ICELLR2,
                COMPATIBLE_ICELLPY);
    }
}
